package com.example.gateway.routes;

import com.example.gateway.routing.InMemoryDynamicRouteLocator;
import com.example.gateway.routing.RouteCompiler;
import com.example.gateway.routing.RouteDefinitionMapper;
import com.example.gateway.routes.store.RouteConfigProvider;
import com.example.shared.routes.RouteAccess;
import com.example.shared.routes.RouteConfigEntry;
import com.example.shared.routes.RouteConfigSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GatewayRouteReloadServiceTest {

    private final InMemoryDynamicRouteLocator locator = new InMemoryDynamicRouteLocator();
    private final RouteDefinitionMapper mapper = new RouteDefinitionMapper();
    private final RouteCompiler compiler = definitions -> definitions.stream()
            .map(rd -> Route.async(rd).asyncPredicate(exchange -> Mono.just(true)).build())
            .toList();

    @Test
    void reloadReplacesRoutesAndPublishesEvent() {
        RouteConfigProvider provider = () -> new RouteConfigSnapshot(
                "v1",
                Instant.parse("2026-06-13T10:00:00Z"),
                List.of(
                        new RouteConfigEntry("a", "/a/**", "http://a", 1, null, true, null, null, null),
                        new RouteConfigEntry("b", "/b/**", "http://b", 1, List.of("GET"), true, null, null, null)
                )
        );
        AtomicInteger refreshEvents = new AtomicInteger();
        ApplicationEventPublisher events = event -> {
            if (event instanceof RefreshRoutesEvent) {
                refreshEvents.incrementAndGet();
            }
        };

        GatewayRouteReloadService service = new GatewayRouteReloadService(provider, mapper, locator, compiler, events);

        List<String> active = service.reloadFromStore();

        assertThat(active).containsExactly("a", "b");
        assertThat(refreshEvents).hasValue(1);
        assertThat(locator.currentDefinitions())
                .extracting(RouteDefinition::getId)
                .containsExactly("a", "b");
        assertThat(locator.currentRoutes())
                .extracting(Route::getId)
                .containsExactly("a", "b");
    }

    @Test
    void reloadOnEmptySnapshotClearsLocatorAfterPriorNonEmptyReload() {
        AtomicReference<List<RouteConfigEntry>> nextSnapshot = new AtomicReference<>(List.of(
                new RouteConfigEntry("a", "/a/**", "http://a", 1, null, true, null, null, null)
        ));
        RouteConfigProvider provider = () -> new RouteConfigSnapshot("v1", Instant.now(), nextSnapshot.get());
        ApplicationEventPublisher events = e -> {};

        GatewayRouteReloadService service = new GatewayRouteReloadService(provider, mapper, locator, compiler, events);
        service.reloadFromStore();
        assertThat(locator.size()).isOne();

        nextSnapshot.set(List.of());
        List<String> active = service.reloadFromStore();

        assertThat(active).isEmpty();
        assertThat(locator.size()).isZero();
        assertThat(locator.currentDefinitions()).isEmpty();
    }

    @Test
    void reloadSkipsWhenSnapshotRoutesAreUnchanged() {
        List<RouteConfigEntry> entries = List.of(
                new RouteConfigEntry("a", "/a/**", "http://a", 1, null, true, null, null, null)
        );
        RouteConfigProvider provider = () -> new RouteConfigSnapshot("v1", Instant.now(), entries);
        AtomicInteger refreshEvents = new AtomicInteger();
        ApplicationEventPublisher events = event -> {
            if (event instanceof RefreshRoutesEvent) {
                refreshEvents.incrementAndGet();
            }
        };

        GatewayRouteReloadService service = new GatewayRouteReloadService(provider, mapper, locator, compiler, events);

        List<String> first = service.reloadFromStore();
        List<String> second = service.reloadFromStore();

        assertThat(first).containsExactly("a");
        assertThat(second).containsExactly("a");
        assertThat(refreshEvents)
                .as("second reload must short-circuit and avoid publishing another refresh event")
                .hasValue(1);
    }

    @Test
    void changingOnlyAccessTriggersReload() {
        assertThat(refreshEventsAcross(
                new RouteAccess(null, List.of("group:x"), null),
                new RouteAccess(null, List.of("group:y"), null)))
                .isEqualTo(2);
    }

    @Test
    void nullAndEmptyAccessAreDistinguished() {
        assertThat(refreshEventsAcross(null, new RouteAccess(null, List.of(), null))).isEqualTo(2);
        assertThat(refreshEventsAcross(
                new RouteAccess(null, null, null),
                new RouteAccess(null, List.of(), null)))
                .isEqualTo(2);
    }

    @Test
    void forwardTokenChangeTriggersReload() {
        assertThat(refreshEventsAcross(
                new RouteAccess("authenticated", null, null),
                new RouteAccess("authenticated", null, false)))
                .isEqualTo(2);
    }

    @Test
    void identicalAccessShortCircuits() {
        assertThat(refreshEventsAcross(
                new RouteAccess("authenticated", List.of("group:x"), true),
                new RouteAccess("authenticated", List.of("group:x"), true)))
                .isEqualTo(1);
    }

    /** Loads two snapshots that differ only by route a's access and counts refresh events. */
    private int refreshEventsAcross(RouteAccess before, RouteAccess after) {
        AtomicReference<RouteAccess> current = new AtomicReference<>(before);
        RouteConfigProvider provider = () -> new RouteConfigSnapshot("v1", Instant.now(), List.of(
                new RouteConfigEntry("a", "/a/**", "http://a", 1, null, true, null, null, current.get())));
        AtomicInteger refreshEvents = new AtomicInteger();
        ApplicationEventPublisher events = event -> {
            if (event instanceof RefreshRoutesEvent) {
                refreshEvents.incrementAndGet();
            }
        };
        GatewayRouteReloadService service = new GatewayRouteReloadService(
                provider, mapper, new InMemoryDynamicRouteLocator(), compiler, events);

        service.reloadFromStore();
        current.set(after);
        service.reloadFromStore();
        return refreshEvents.get();
    }

    @Test
    void reloadDelegatesCompilationToRouteCompiler() {
        RouteConfigProvider provider = () -> new RouteConfigSnapshot(
                "v1",
                Instant.now(),
                List.of(new RouteConfigEntry("a", "/a/**", "http://a", 1, null, true, null, null, null))
        );
        AtomicReference<List<RouteDefinition>> captured = new AtomicReference<>();
        RouteCompiler capturingCompiler = definitions -> {
            captured.set(definitions);
            return definitions.stream()
                    .map(rd -> Route.async(rd).asyncPredicate(e -> Mono.just(true)).build())
                    .toList();
        };

        GatewayRouteReloadService service = new GatewayRouteReloadService(
                provider, mapper, locator, capturingCompiler, e -> {});

        service.reloadFromStore();

        assertThat(captured.get())
                .as("reload service must hand definitions over to the compiler")
                .hasSize(1)
                .first()
                .extracting(RouteDefinition::getId).isEqualTo("a");
    }

    @Test
    void reloadPropagatesProviderException() {
        RouteConfigProvider provider = () -> { throw new IllegalStateException("boom"); };
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

        GatewayRouteReloadService service = new GatewayRouteReloadService(provider, mapper, locator, compiler, events);

        assertThatThrownBy(service::reloadFromStore)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }

    @Test
    void reloadPropagatesEventPublisherFailure() {
        RouteConfigProvider provider = () -> new RouteConfigSnapshot(
                "v1",
                Instant.now(),
                List.of(new RouteConfigEntry("a", "/a/**", "http://a", 1, null, true, null, null, null))
        );
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        doThrow(new IllegalStateException("publisher failed")).when(events).publishEvent(any());

        GatewayRouteReloadService service = new GatewayRouteReloadService(provider, mapper, locator, compiler, events);

        assertThatThrownBy(service::reloadFromStore).isInstanceOf(IllegalStateException.class);
        verify(events).publishEvent(any());
    }

    // The store watcher and the operator's POST /internal/routes/reload can reload at the same
    // time: a slow reload of an older snapshot must not land on top of a newer one.
    @Test
    void concurrentReloadsNeverLeaveAnOlderSnapshotInPlace() throws Exception {
        CountDownLatch firstLoadStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstLoad = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        RouteConfigProvider provider = () -> {
            if (loads.incrementAndGet() == 1) {
                firstLoadStarted.countDown();
                try {
                    releaseFirstLoad.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return singleRouteSnapshot("older");
            }
            return singleRouteSnapshot("newer");
        };
        GatewayRouteReloadService service = new GatewayRouteReloadService(provider, mapper, locator, compiler, e -> {});

        Thread slow = Thread.ofPlatform().start(service::reloadFromStore);
        firstLoadStarted.await();
        Thread fast = Thread.ofPlatform().start(service::reloadFromStore);
        await().atMost(Duration.ofSeconds(5))
                .until(() -> fast.getState() == Thread.State.BLOCKED || !fast.isAlive());
        releaseFirstLoad.countDown();
        slow.join();
        fast.join();

        assertThat(locator.currentDefinitions()).extracting(RouteDefinition::getId).containsExactly("newer");
        assertThat(service.reloadFromStore()).containsExactly("newer");
        assertThat(loads).hasValue(3);
    }

    private static RouteConfigSnapshot singleRouteSnapshot(String id) {
        return new RouteConfigSnapshot(id, Instant.now(), List.of(
                new RouteConfigEntry(id, "/" + id + "/**", "http://" + id, 1, null, true, null, null, null)));
    }

    private RouteDefinition definition(String id) {
        return mapper.toDefinition(new RouteConfigEntry(id, "/x/**", "http://x", 1, null, true, null, null, null));
    }
}
