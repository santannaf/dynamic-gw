package com.example.shared.routes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Time limits of the call to the route's backend, in the route duration format
 * ({@code 500ms}, {@code 2s}, {@code 1m}). {@code connect} bounds opening the
 * connection; {@code response} runs from the start of the call, connection
 * included, until the response headers arrive, so a slow body is never cut.
 * {@code null} means the gateway's global value.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RouteTimeouts(
        String connect,
        String response
) {
}
