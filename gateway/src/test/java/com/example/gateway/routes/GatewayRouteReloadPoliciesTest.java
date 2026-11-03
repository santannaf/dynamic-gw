package com.example.gateway.routes;

import com.example.gateway.routing.InMemoryDynamicRouteLocator;
import com.example.gateway.routing.RouteCompiler;
import com.example.gateway.routing.RouteDefinitionMapper;
import com.example.gateway.routes.store.RouteConfigProvider;
import com.example.shared.routes.RouteConfigEntry;
import com.example.shared.routes.RouteConfigSnapshot;
import com.example.shared.routes.RouteFilter;
import com.example.shared.routes.RouteRateLimit;
import com.example.shared.routes.RouteThrottle;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayRouteReloadPoliciesTest {

    private final RouteDefinitionMapper mapper = new RouteDefinitionMapper();
    private final RouteCompiler compiler = definitions -> definitions.stream()
            .map(rd -> Route.async(rd).asyncPredicate(exchange -> Mono.just(true)).build())
            .toList();

    @Test
    void changingOnlyARateLimitTriggersReload() {
        assertThat(refreshEventsAcross(route(new RouteRateLimit(10, "1s", null, null), null),
                route(new RouteRateLimit(20, "1s", null, null), null))).isEqualTo(2);
    }

    @Test
    void changingOnlyAThrottleTriggersReload() {
        assertThat(refreshEventsAcross(route(null, new RouteThrottle(5, null, null)),
                route(null, new RouteThrottle(5, 1, null)))).isEqualTo(2);
    }

    @Test
    void identicalPoliciesShortCircuit() {
        assertThat(refreshEventsAcross(route(new RouteRateLimit(10, "1s", 5, "ip"), new RouteThrottle(5, 1, "1s")),
                route(new RouteRateLimit(10, "1s", 5, "ip"), new RouteThrottle(5, 1, "1s")))).isEqualTo(1);
    }

    @Test
    void routeWithAnInvalidPolicyIsNotServed() {
        RouteConfigEntry forged = new RouteConfigEntry("forged", "/f/**", "http://f", 1, null, true, null, null, null,
                null, null, List.of(new RouteFilter("SetRequestHeader", Map.of("name", "X-Auth-Request-User", "value", "admin"))),
                null, null);
        RouteConfigEntry broken = new RouteConfigEntry("broken", "/b/**", "http://b", 1, null, true, null, null, null,
                new RouteRateLimit(0, "1s", null, null), null, null, null, null);
        RouteConfigEntry fine = route(new RouteRateLimit(10, "1s", null, null), null);
        RouteConfigProvider provider = () -> new RouteConfigSnapshot("v1", Instant.now(), List.of(broken, fine, forged));
        InMemoryDynamicRouteLocator locator = new InMemoryDynamicRouteLocator();

        List<String> active = new GatewayRouteReloadService(provider, mapper, locator, compiler, event -> {})
                .reloadFromStore();

        assertThat(active).containsExactly("a");
    }

    @Test
    void identityHeaderRuleFollowsTheConfiguredPrefix() {
        RouteConfigEntry route = new RouteConfigEntry("a", "/a/**", "http://a", 1, null, true, null, null, null,
                null, null, List.of(new RouteFilter("AddRequestHeader", Map.of("name", "X-Id-User", "value", "x"))),
                null, null);
        RouteConfigProvider provider = () -> new RouteConfigSnapshot("v1", Instant.now(), List.of(route));

        List<String> active = new GatewayRouteReloadService(provider, mapper, new InMemoryDynamicRouteLocator(),
                compiler, event -> {}, "X-Id-").reloadFromStore();

        assertThat(active).isEmpty();
    }

    private static RouteConfigEntry route(RouteRateLimit rateLimit, RouteThrottle throttle) {
        return new RouteConfigEntry("a", "/a/**", "http://a", 1, null, true, null, null, null,
                rateLimit, throttle, null, null, null);
    }

    private int refreshEventsAcross(RouteConfigEntry before, RouteConfigEntry after) {
        AtomicReference<RouteConfigEntry> current = new AtomicReference<>(before);
        RouteConfigProvider provider = () -> new RouteConfigSnapshot("v1", Instant.now(), List.of(current.get()));
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
}
