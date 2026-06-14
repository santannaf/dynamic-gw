package com.example.shared.routes;

import java.time.Instant;
import java.util.List;

public record RouteConfigSnapshot(
        String version,
        Instant generatedAt,
        List<RouteConfigEntry> routes
) {
    public RouteConfigSnapshot {
        routes = routes == null ? List.of() : List.copyOf(routes);
    }

    public static RouteConfigSnapshot empty() {
        return new RouteConfigSnapshot("empty", Instant.EPOCH, List.of());
    }
}
