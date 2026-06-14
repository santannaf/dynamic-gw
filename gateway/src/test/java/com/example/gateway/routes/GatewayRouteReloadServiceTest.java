package com.example.gateway.routes;

import com.example.gateway.routing.InMemoryDynamicRouteDefinitionRepository;
import com.example.gateway.routing.RouteDefinitionMapper;
import com.example.gateway.routes.store.RouteConfigProvider;
import com.example.shared.routes.RouteConfigEntry;
import com.example.shared.routes.RouteConfigSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GatewayRouteReloadServiceTest {

    private final InMemoryDynamicRouteDefinitionRepository repo = new InMemoryDynamicRouteDefinitionRepository();
    private final RouteDefinitionMapper mapper = new RouteDefinitionMapper();

    @Test
    void reloadReplacesRoutesAndPublishesEvent() {
        RouteConfigProvider provider = () -> new RouteConfigSnapshot(
                "v1",
                Instant.parse("2026-06-13T10:00:00Z"),
                List.of(
                        new RouteConfigEntry("a", "/a/**", "http://a", 1, null, true),
                        new RouteConfigEntry("b", "/b/**", "http://b", 1, List.of("GET"), true)
                )
        );
        AtomicInteger refreshEvents = new AtomicInteger();
        ApplicationEventPublisher events = event -> {
            if (event instanceof RefreshRoutesEvent) {
                refreshEvents.incrementAndGet();
            }
        };

        GatewayRouteReloadService service = new GatewayRouteReloadService(provider, mapper, repo, events);

        List<String> active = service.reloadFromStore();

        assertThat(active).containsExactly("a", "b");
        assertThat(refreshEvents).hasValue(1);
        assertThat(repo.getRouteDefinitions().map(RouteDefinition::getId).collectList().block())
                .containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void reloadOnEmptySnapshotClearsRepository() {
        repo.replaceAll(List.of(definition("stale")));
        RouteConfigProvider provider = () -> new RouteConfigSnapshot("v1", Instant.now(), List.of());
        ApplicationEventPublisher events = e -> {};

        GatewayRouteReloadService service = new GatewayRouteReloadService(provider, mapper, repo, events);
        List<String> active = service.reloadFromStore();

        assertThat(active).isEmpty();
        assertThat(repo.size()).isZero();
    }

    @Test
    void reloadPropagatesProviderException() {
        RouteConfigProvider provider = () -> { throw new IllegalStateException("boom"); };
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

        GatewayRouteReloadService service = new GatewayRouteReloadService(provider, mapper, repo, events);

        assertThatThrownBy(service::reloadFromStore)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }

    @Test
    void reloadPropagatesEventPublisherFailure() {
        RouteConfigProvider provider = () -> new RouteConfigSnapshot("v1", Instant.now(), List.of());
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        doThrow(new IllegalStateException("publisher failed")).when(events).publishEvent(any());

        GatewayRouteReloadService service = new GatewayRouteReloadService(provider, mapper, repo, events);

        assertThatThrownBy(service::reloadFromStore).isInstanceOf(IllegalStateException.class);
        verify(events).publishEvent(any());
    }

    private RouteDefinition definition(String id) {
        return mapper.toDefinition(new RouteConfigEntry(id, "/x/**", "http://x", 1, null, true));
    }
}
