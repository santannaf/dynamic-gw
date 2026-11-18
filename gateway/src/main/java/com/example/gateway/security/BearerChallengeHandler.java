package com.example.gateway.security;

import com.example.gateway.access.AccessAttributes;
import com.example.gateway.access.AccessDecision;
import com.example.gateway.access.AccessPolicy;
import com.example.gateway.access.UnauthenticatedHandler;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Answers 401 with the {@code WWW-Authenticate} challenge of RFC 6750, and the
 * {@code insufficient_user_authentication} error of RFC 9470 when the session
 * came from another login than the route's identity provider.
 */
public class BearerChallengeHandler implements UnauthenticatedHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDecision decision) {
        String challenge = switch (decision) {
            case INVALID_TOKEN -> "Bearer realm=\"dynamic-gateway\", error=\"invalid_token\"";
            case WRONG_IDENTITY_PROVIDER -> "Bearer realm=\"dynamic-gateway\", error=\"insufficient_user_authentication\","
                    + " error_description=\"log in through " + identityProvider(exchange) + "\"";
            case null, default -> "Bearer realm=\"dynamic-gateway\"";
        };
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, challenge);
        return exchange.getResponse().setComplete();
    }

    // The route's alias passed RoutePolicyRules, so it holds no quote to escape.
    private static String identityProvider(ServerWebExchange exchange) {
        AccessPolicy policy = exchange.getAttribute(AccessAttributes.POLICY);
        return policy == null ? "" : policy.identityProvider();
    }
}
