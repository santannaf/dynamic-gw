package com.example.shared.routes;

import java.util.List;

/**
 * One immutable route as it travels from the operator/source-of-truth to the
 * gateway's in-memory route table.
 *
 * Fields:
 * - {@code id, path, targetUri, stripPrefix, methods, enabled}: routing
 *   behavior (consumed by {@code RouteDefinitionMapper}).
 * - {@code team, description}: ownership/documentation metadata. Optional;
 *   does not affect matching. Surfaced by {@code /internal/routes} and
 *   included in the snapshot hash so changes to ownership still trigger
 *   a reload.
 * - {@code access}: who may use the route. {@code null} means "not declared"
 *   (the gateway applies its default access). Enforced by the gateway, never
 *   by the matcher, and part of the snapshot hash.
 * - {@code rateLimit, throttle, filters, transform, aggregate}: the route's
 *   policies ({@link RoutePolicyRules} validates them). {@code null} means not
 *   declared. A route declares either {@code targetUri} or {@code aggregate}.
 */
public record RouteConfigEntry(
        String id,
        String path,
        String targetUri,
        Integer stripPrefix,
        List<String> methods,
        Boolean enabled,
        String team,
        String description,
        RouteAccess access,
        RouteRateLimit rateLimit,
        RouteThrottle throttle,
        List<RouteFilter> filters,
        RouteTransform transform,
        RouteAggregate aggregate
) {
    public RouteConfigEntry {
        methods = methods == null ? null : List.copyOf(methods);
        filters = filters == null ? null : List.copyOf(filters);
    }
}
