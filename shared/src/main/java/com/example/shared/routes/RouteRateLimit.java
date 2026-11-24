package com.example.shared.routes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Requests per period on one route, counted per gateway replica.
 *
 * {@code period} uses the route duration format ({@code 500ms}, {@code 1s},
 * {@code 1m}, {@code 1h}). {@code burst} is how many requests may arrive at
 * once; {@code null} means {@code requests}. {@code key} decides whose budget a
 * request spends: {@code null} or {@code route} for everyone together,
 * {@code ip}, {@code user}, {@code client-id} or {@code header:<name>} for
 * one budget per caller.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RouteRateLimit(
        Integer requests,
        String period,
        Integer burst,
        String key
) {
}
