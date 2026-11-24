package com.example.gateway.security;

import com.example.gateway.access.AccessAttributes;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Runs right after Spring Security's chain (order -100) and publishes the
 * caller's identity as a plain exchange attribute, so the route filter and
 * the audit filter do not depend on Spring Security types.
 */
public class IdentityCaptureWebFilter implements WebFilter, Ordered {

    public static final int ORDER = -90;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return exchange.getPrincipal()
                .filter(GatewayAuthenticationToken.class::isInstance)
                .cast(GatewayAuthenticationToken.class)
                .doOnNext(token -> exchange.getAttributes().put(AccessAttributes.IDENTITY, token.identity()))
                .then(Mono.defer(() -> chain.filter(exchange)));
    }
}
