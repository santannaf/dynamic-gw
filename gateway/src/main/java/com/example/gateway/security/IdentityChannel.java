package com.example.gateway.security;

import com.example.gateway.access.UnauthenticatedHandler;
import com.example.gateway.security.session.GatewaySessionManager;
import org.springframework.security.config.web.server.ServerHttpSecurity;

import java.time.Clock;
import java.util.Set;

/**
 * One way of finding out who is calling. Each edge gateway has its own: a new
 * channel implements this interface and registers its factory in
 * {@link ActiveSecurity}, and routes, enforcement, identity headers and audit
 * stay the same.
 *
 * Channels are plain objects picked at startup rather than
 * {@code @Configuration} classes: the native image evaluates
 * {@code @Conditional} at build time and would stay locked to the build's mode.
 */
public interface IdentityChannel extends AutoCloseable {

    /** Adds this channel's credentials to the chain, on top of {@link SecurityChains#common}. */
    ServerHttpSecurity configure(ServerHttpSecurity http);

    UnauthenticatedHandler unauthenticatedHandler();

    /** Browser login of this channel; {@code null} when it has none, as in a token-only API. */
    default BrowserLoginHandler browserLogin() {
        return null;
    }

    /** Session of this channel; {@code null} when it has none, as in a token-only API. */
    default GatewaySessionManager sessionManager() {
        return null;
    }

    /** Cookies owned by this channel, which never reach upstream services. */
    default Set<String> gatewayCookieNames() {
        return Set.of();
    }

    @Override
    default void close() {
    }

    @FunctionalInterface
    interface Factory {
        /** Fails on missing configuration, so a broken setup stops the startup. */
        IdentityChannel create(GatewaySecurityProperties properties, Clock clock);
    }
}
