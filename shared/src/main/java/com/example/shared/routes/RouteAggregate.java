package com.example.shared.routes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Calls every backend in parallel and answers with one JSON object. A route
 * declares either {@code targetUri} or {@code aggregate}. {@code timeout}
 * bounds the whole fan-out; {@code null} means the gateway's default.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RouteAggregate(
        List<RouteBackend> backends,
        String timeout
) {
    public RouteAggregate {
        backends = backends == null ? null : List.copyOf(backends);
    }
}
