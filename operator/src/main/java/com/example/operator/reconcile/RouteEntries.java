package com.example.operator.reconcile;

import com.example.operator.crd.GatewayRoute;
import com.example.operator.crd.GatewayRouteAccess;
import com.example.operator.crd.GatewayRouteSpec;
import com.example.shared.routes.RouteAccess;
import com.example.shared.routes.RouteConfigEntry;

/** How a GatewayRoute becomes a snapshot entry; the validator checks this same entry. */
final class RouteEntries {

    private RouteEntries() {
    }

    static RouteConfigEntry toEntry(GatewayRoute route) {
        GatewayRouteSpec spec = route.getSpec();
        return new RouteConfigEntry(
                route.getMetadata().getName(),
                spec.getPath(),
                spec.getTargetUri(),
                spec.getStripPrefix(),
                spec.getMethods(),
                Boolean.TRUE,
                spec.getTeam(),
                spec.getDescription(),
                toAccess(spec.getAccess()),
                spec.getRateLimit(),
                spec.getThrottle(),
                spec.getFilters(),
                spec.getTransform(),
                spec.getAggregate()
        );
    }

    private static RouteAccess toAccess(GatewayRouteAccess access) {
        if (access == null) {
            return null;
        }
        return new RouteAccess(access.getMode(), access.getAnyOf(), access.getForwardToken());
    }
}
