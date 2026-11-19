package com.example.gateway.access;

import reactor.core.publisher.Mono;

/**
 * The token the backend receives when the route picks one
 * ({@code access.audience} or {@code access.obo}) instead of the caller's own.
 * The identity channel implements it; the route filters only know this port.
 */
public interface UpstreamTokens {

    UpstreamTokens NONE = (identity, policy) -> Mono.error(new UpstreamTokenException(
            UpstreamTokenException.Reason.REFUSED, "this identity channel picks no token for the backend"));

    /** Fails with {@link UpstreamTokenException} when there is no token to send. */
    Mono<String> tokenFor(GatewayIdentity identity, AccessPolicy policy);
}
