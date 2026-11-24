package com.example.gateway.access;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * What a caller without identity receives on a route that requires one. The
 * answer belongs to the identity channel: a browser login, a redirect to a
 * portal and a bare 401 are all valid. The route filter only knows this port.
 */
public interface UnauthenticatedHandler {

    UnauthenticatedHandler PLAIN_401 = (exchange, decision) -> {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    };

    Mono<Void> handle(ServerWebExchange exchange, AccessDecision decision);
}
