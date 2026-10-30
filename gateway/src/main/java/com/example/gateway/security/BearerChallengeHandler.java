package com.example.gateway.security;

import com.example.gateway.access.AccessDecision;
import com.example.gateway.access.UnauthenticatedHandler;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Answers 401 with the {@code WWW-Authenticate} challenge of RFC 6750. */
public class BearerChallengeHandler implements UnauthenticatedHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDecision decision) {
        String challenge = decision == AccessDecision.INVALID_TOKEN
                ? "Bearer realm=\"dynamic-gateway\", error=\"invalid_token\""
                : "Bearer realm=\"dynamic-gateway\"";
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, challenge);
        return exchange.getResponse().setComplete();
    }
}
