package com.example.gateway.security.session;

import com.example.gateway.access.GatewayIdentity;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * Provider-specific side of the session: turning the session cookie into an
 * identity (revalidating with the provider when the replica's cache has no
 * valid entry) and ending the session on logout. Session establishment is
 * provider-specific too and lives on the implementations.
 */
public interface GatewaySessionManager {

    /**
     * Identity carried by the request's session cookie, or empty. Implementations
     * mark {@code AccessAttributes.PROBLEM} when a cookie existed but no
     * identity could be produced, and expire the cookie when it is no longer valid.
     */
    Mono<GatewayIdentity> resolve(ServerWebExchange exchange);

    /** Ends the session for this browser and returns where to send it next. */
    Mono<URI> logout(ServerWebExchange exchange);
}
