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
 */
public record RouteConfigEntry(
        String id,
        String path,
        String targetUri,
        Integer stripPrefix,
        List<String> methods,
        Boolean enabled,
        String team,
        String description
) {
    public RouteConfigEntry {
        methods = methods == null ? null : List.copyOf(methods);
    }
}
