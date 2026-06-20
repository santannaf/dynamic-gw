package com.example.gateway.routes;

import com.example.gateway.routing.InMemoryDynamicRouteLocator;
import com.example.gateway.routing.RouteCompiler;
import com.example.gateway.routing.RouteDefinitionMapper;
import com.example.gateway.routes.store.RouteConfigProvider;
import com.example.shared.routes.RouteConfigEntry;
import com.example.shared.routes.RouteConfigSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
                        new RouteConfigEntry("a", "/a/**", "http://a", 1, null, true, null, null),
                        new RouteConfigEntry("b", "/b/**", "http://b", 1, List.of("GET"), true, null, null)
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
                new RouteConfigEntry("a", "/a/**", "http://a", 1, null, true, null, null)
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
                new RouteConfigEntry("a", "/a/**", "http://a", 1, null, true, null, null)
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
    void reloadDelegatesCompilationToRouteCompiler() {
        RouteConfigProvider provider = () -> new RouteConfigSnapshot(
                "v1",
                Instant.now(),
                List.of(new RouteConfigEntry("a", "/a/**", "http://a", 1, null, true, null, null))
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
                List.of(new RouteConfigEntry("a", "/a/**", "http://a", 1, null, true, null, null))
        );
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        doThrow(new IllegalStateException("publisher failed")).when(events).publishEvent(any());

        GatewayRouteReloadService service = new GatewayRouteReloadService(provider, mapper, locator, compiler, events);

        assertThatThrownBy(service::reloadFromStore).isInstanceOf(IllegalStateException.class);
        verify(events).publishEvent(any());
    }

    private RouteDefinition definition(String id) {
        return mapper.toDefinition(new RouteConfigEntry(id, "/x/**", "http://x", 1, null, true, null, null));
    }
}
