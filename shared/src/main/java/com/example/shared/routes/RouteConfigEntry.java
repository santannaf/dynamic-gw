package com.example.shared.routes;

import java.util.List;

public record RouteConfigEntry(
        String id,
        String path,
        String targetUri,
        Integer stripPrefix,
        List<String> methods,
        Boolean enabled
) {
    public RouteConfigEntry {
        methods = methods == null ? null : List.copyOf(methods);
    }
}
