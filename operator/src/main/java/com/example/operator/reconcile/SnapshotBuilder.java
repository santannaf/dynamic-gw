package com.example.operator.reconcile;

import com.example.operator.crd.GatewayRoute;
import com.example.operator.crd.GatewayRouteSpec;
import com.example.shared.routes.RouteConfigEntry;
import com.example.shared.routes.RouteConfigSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public class SnapshotBuilder {

    private static final Logger log = LoggerFactory.getLogger(SnapshotBuilder.class);

    private final GatewayRouteValidator validator;

    public SnapshotBuilder(GatewayRouteValidator validator) { this.validator = validator; }

    public Result build(List<GatewayRoute> all, Clock clock) {
        int total = all.size();
        int disabled = 0;
        int invalid = 0;

        List<RouteConfigEntry> entries = new java.util.ArrayList<>();
        for (GatewayRoute route : all) {
            GatewayRouteSpec spec = route.getSpec();
            if (spec != null && Boolean.FALSE.equals(spec.getEnabled())) {
                disabled++;
                continue;
            }
            var error = validator.validate(route);
            if (error.isPresent()) {
                invalid++;
                log.warn("GatewayRoute {} is invalid: {}", routeName(route), error.get());
                continue;
            }
            entries.add(toEntry(route));
        }
        entries.sort(Comparator.comparing(RouteConfigEntry::id));

        Instant now = clock.instant();
        RouteConfigSnapshot snapshot = new RouteConfigSnapshot(now.toString(), now, entries);
        return new Result(snapshot, total, entries.size(), disabled, invalid);
    }

    private static RouteConfigEntry toEntry(GatewayRoute route) {
        GatewayRouteSpec spec = route.getSpec();
        return new RouteConfigEntry(
                route.getMetadata().getName(),
                spec.getPath(),
                spec.getTargetUri(),
                spec.getStripPrefix(),
                spec.getMethods(),
                Boolean.TRUE,
                spec.getTeam(),
                spec.getDescription()
        );
    }

    private static String routeName(GatewayRoute route) {
        return route.getMetadata() != null ? route.getMetadata().getName() : "<unknown>";
    }

    public record Result(RouteConfigSnapshot snapshot, int total, int valid, int disabled, int invalid) {
    }
}
