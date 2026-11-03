package com.example.operator.reconcile;

import com.example.operator.crd.GatewayRoute;
import com.example.shared.routes.RouteAggregate;
import com.example.shared.routes.RouteBackend;
import com.example.shared.routes.RouteConfigEntry;
import com.example.shared.routes.RouteFilter;
import com.example.shared.routes.RouteRateLimit;
import com.example.shared.routes.RouteThrottle;
import com.example.shared.routes.RouteTransform;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static com.example.operator.reconcile.GatewayRouteValidatorTest.route;
import static org.assertj.core.api.Assertions.assertThat;

class GatewayRoutePoliciesTest {

    private final GatewayRouteValidator validator = new GatewayRouteValidator();
    private final SnapshotBuilder builder = new SnapshotBuilder(validator);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void policiesAreCopiedIntoTheSnapshotAsDeclared() {
        GatewayRoute route = route("pedidos", "/pedidos/**", "http://pedidos", 1, null, true);
        RouteRateLimit rateLimit = new RouteRateLimit(100, "1s", null, "ip");
        RouteThrottle throttle = new RouteThrottle(20, 50, "200ms");
        List<RouteFilter> filters = List.of(new RouteFilter("AddRequestHeader", Map.of("name", "X-Source", "value", "gw")));
        RouteTransform transform = new RouteTransform(null, List.of("senha"), null, null, null);
        route.getSpec().setRateLimit(rateLimit);
        route.getSpec().setThrottle(throttle);
        route.getSpec().setFilters(filters);
        route.getSpec().setTransform(transform);

        RouteConfigEntry entry = builder.build(List.of(route), clock).snapshot().routes().getFirst();

        assertThat(entry.rateLimit()).isEqualTo(rateLimit);
        assertThat(entry.throttle()).isEqualTo(throttle);
        assertThat(entry.filters()).isEqualTo(filters);
        assertThat(entry.transform()).isEqualTo(transform);
        assertThat(entry.aggregate()).isNull();
    }

    @Test
    void aggregatedRouteNeedsNoTargetUri() {
        GatewayRoute route = route("painel", "/painel/{id}", null, 1, null, true);
        route.getSpec().setAggregate(new RouteAggregate(List.of(
                new RouteBackend("http://pedidos/pedidos/{id}", "pedido", null),
                new RouteBackend("http://clientes/clientes/{id}", "cliente", null)), "2s"));

        assertThat(validator.validate(route)).isEmpty();
        assertThat(builder.build(List.of(route), clock).snapshot().routes())
                .singleElement()
                .satisfies(entry -> assertThat(entry.aggregate().backends()).hasSize(2));
    }

    @Test
    void routeWithoutTargetOrAggregateIsLeftOut() {
        GatewayRoute route = route("nowhere", "/x/**", null, 1, null, true);

        assertThat(validator.validate(route))
                .hasValueSatisfying(problem -> assertThat(problem).isEqualTo(
                        "spec.targetUri is required (or aggregate, to combine several backends)"));
        assertThat(builder.build(List.of(route), clock).invalid()).isEqualTo(1);
    }

    @Test
    void invalidPolicyKeepsTheRouteOutWithTheSpecPath() {
        GatewayRoute route = route("forged", "/x/**", "http://x", 1, null, true);
        route.getSpec().setFilters(List.of(
                new RouteFilter("SetRequestHeader", Map.of("name", "X-Auth-Request-User", "value", "admin"))));

        assertThat(validator.validate(route))
                .hasValueSatisfying(problem -> assertThat(problem)
                        .startsWith("spec.filters[0] (SetRequestHeader) must not touch the identity header"));
        assertThat(builder.build(List.of(route), clock).snapshot().routes()).isEmpty();
    }
}
