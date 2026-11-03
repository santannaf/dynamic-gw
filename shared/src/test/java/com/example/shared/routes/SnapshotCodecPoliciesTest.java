package com.example.shared.routes;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotCodecPoliciesTest {

    private final SnapshotCodec codec = new SnapshotCodec();

    @Test
    void policiesSurviveYamlAndGzipRoundTrips() {
        RouteConfigEntry proxied = new RouteConfigEntry("pedidos", "/pedidos/**", "http://pedidos", 1, List.of("GET"),
                true, "pedidos", null, null,
                new RouteRateLimit(100, "1s", 20, "ip"),
                new RouteThrottle(50, 100, "200ms"),
                List.of(new RouteFilter("AddRequestHeader", Map.of("name", "X-Source", "value", "gateway")),
                        new RouteFilter("RewritePath", Map.of("regexp", "/pedidos/(?<rest>.*)", "replacement", "/v2/${rest}"))),
                new RouteTransform(null, List.of("senha"), Map.of("nome", "name"), "data", "pedido"),
                null);
        RouteConfigEntry aggregated = new RouteConfigEntry("painel", "/painel/{id}", null, 1, null, true, null, null,
                null, null, null, null, null,
                new RouteAggregate(List.of(
                        new RouteBackend("http://pedidos/pedidos/{id}", "pedido", new RouteTransform(List.of("id"), null, null, null, null)),
                        new RouteBackend("http://clientes/clientes/{id}", "cliente", null)), "2s"));
        RouteConfigSnapshot snapshot = new RouteConfigSnapshot("v1", Instant.parse("2026-11-03T12:00:00Z"),
                List.of(aggregated, proxied));

        assertThat(codec.readYaml(codec.writeYaml(snapshot))).isEqualTo(snapshot);
        assertThat(codec.readYamlGz(codec.writeYamlGz(snapshot))).isEqualTo(snapshot);
    }

    @Test
    void snapshotWithoutPoliciesStillLoads() {
        String legacy = """
                version: v0
                generatedAt: 2026-10-01T00:00:00Z
                routes:
                  - id: a
                    path: /a/**
                    targetUri: http://a
                    stripPrefix: 1
                    enabled: true
                """;

        RouteConfigEntry route = codec.readYaml(legacy).routes().getFirst();

        assertThat(route.targetUri()).isEqualTo("http://a");
        assertThat(route.rateLimit()).isNull();
        assertThat(route.throttle()).isNull();
        assertThat(route.filters()).isNull();
        assertThat(route.transform()).isNull();
        assertThat(route.aggregate()).isNull();
    }

    @Test
    void filterArgumentsKeepTheirDeclaredOrder() {
        Map<String, String> args = new LinkedHashMap<>();
        args.put("regexp", "/a");
        args.put("replacement", "/b");
        RouteFilter filter = new RouteFilter("RewritePath", args);
        RouteConfigEntry route = new RouteConfigEntry("a", "/a/**", "http://a", 1, null, true, null, null, null,
                null, null, List.of(filter), null, null);
        RouteConfigSnapshot snapshot = new RouteConfigSnapshot("v1", Instant.parse("2026-11-03T12:00:00Z"), List.of(route));

        RouteFilter read = codec.readYaml(codec.writeYaml(snapshot)).routes().getFirst().filters().getFirst();

        assertThat(read.args().keySet()).containsExactly("regexp", "replacement");
    }
}
