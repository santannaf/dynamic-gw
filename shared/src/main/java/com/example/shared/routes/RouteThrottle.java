package com.example.shared.routes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Concurrency cap of one route, per gateway replica: at most
 * {@code maxConcurrent} requests run at once, up to {@code maxQueue} more wait
 * for a slot for at most {@code maxWait}, and the rest are refused.
 * {@code null} means 0 for {@code maxQueue} and for {@code maxWait}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RouteThrottle(
        Integer maxConcurrent,
        Integer maxQueue,
        String maxWait
) {
}
