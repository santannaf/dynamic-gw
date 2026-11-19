package com.example.gateway.access;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Sends the backend the token its route picks ({@code access.audience} or
 * {@code access.obo}). Runs after the rate limit and the throttling, so a
 * refused request costs no call to the identity provider, and before the
 * aggregation and the proxy.
 */
public class UpstreamTokenFilter implements GlobalFilter, Ordered {

    // RateLimitFilter is RouteAccessFilter.ORDER + 10 and ThrottleFilter + 20; they
    // live in traffic/, which imports this package, so the order is written here.
    public static final int ORDER = RouteAccessFilter.ORDER + 30;

    private static final Logger log = LoggerFactory.getLogger(UpstreamTokenFilter.class);

    private final UpstreamTokens tokens;
    private final UnauthenticatedHandler loginAgain;

    public UpstreamTokenFilter(UpstreamTokens tokens, UnauthenticatedHandler loginAgain) {
        this.tokens = tokens;
        this.loginAgain = loginAgain;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        AccessPolicy policy = exchange.getAttribute(AccessAttributes.POLICY);
        GatewayIdentity identity = exchange.getAttribute(AccessAttributes.IDENTITY);
        if (policy == null || identity == null || !policy.picksUpstreamToken()) {
            return chain.filter(exchange);
        }
        return tokens.tokenFor(identity, policy)
                .switchIfEmpty(Mono.error(() -> new UpstreamTokenException(UpstreamTokenException.Reason.REFUSED,
                        "the identity channel returned no token")))
                .onErrorResume(UpstreamTokenException.class, e -> refuse(exchange, identity, e).then(Mono.empty()))
                .flatMap(token -> chain.filter(exchange.mutate()
                        .request(request -> request.headers(headers -> headers.setBearerAuth(token)))
                        .build()));
    }

    private Mono<Void> refuse(ServerWebExchange exchange, GatewayIdentity identity, UpstreamTokenException e) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        log.warn("Route {} got no token for its backend ({}): {}", route == null ? "?" : route.getId(), e.reason(),
                e.getMessage());
        if (e.reason() == UpstreamTokenException.Reason.LOGIN_REQUIRED) {
            exchange.getAttributes().put(AccessAttributes.DECISION, AccessDecision.IDENTITY_PROVIDER_LOGIN_REQUIRED);
            if (identity.method() == GatewayIdentity.AuthMethod.BEARER) {
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return exchange.getResponse().setComplete();
            }
            return loginAgain.handle(exchange, AccessDecision.IDENTITY_PROVIDER_LOGIN_REQUIRED);
        }
        exchange.getAttributes().put(AccessAttributes.DECISION, AccessDecision.TOKEN_EXCHANGE_FAILED);
        exchange.getResponse().setStatusCode(e.reason() == UpstreamTokenException.Reason.UNAVAILABLE
                ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY);
        return exchange.getResponse().setComplete();
    }
}
