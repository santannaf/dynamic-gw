package com.example.gateway.access;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The identity channel of a route ({@code access.channel}, or the main one when
 * the route names none): who the caller is there, and what that channel
 * answers a caller it does not know.
 */
public interface AccessChannels {

    Mono<GatewayIdentity> identity(ServerWebExchange exchange, String channel);

    UnauthenticatedHandler unauthenticatedHandler(String channel);

    /** A gateway with one channel: the caller the security layer left in {@link AccessAttributes#IDENTITY}. */
    static AccessChannels single(UnauthenticatedHandler handler) {
        return new AccessChannels() {
            @Override
            public Mono<GatewayIdentity> identity(ServerWebExchange exchange, String channel) {
                return Mono.justOrEmpty(exchange.<GatewayIdentity>getAttribute(AccessAttributes.IDENTITY));
            }

            @Override
            public UnauthenticatedHandler unauthenticatedHandler(String channel) {
                return handler;
            }
        };
    }
}
