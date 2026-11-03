package com.example.gateway.traffic;

import com.example.gateway.access.AccessAttributes;
import com.example.gateway.access.AccessDecision;
import com.example.gateway.access.RouteAccessFilter;
import com.example.gateway.routing.RouteDefinitionMapper;
import com.example.shared.routes.RouteRateLimit;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Requests per period: first the global ceiling ({@code gateway.traffic.rate-limit}),
 * then the route's {@code rateLimit}. A refused request gets 429 with
 * {@code Retry-After}; every answer of a limited route carries the
 * {@code RateLimit-*} headers of its tightest budget. Runs after the access
 * check, so {@code user} and {@code client-id} keys see the identity, and
 * anonymous requests to protected routes never spend a budget.
 */
public class RateLimitFilter implements GlobalFilter, Ordered {

    public static final int ORDER = RouteAccessFilter.ORDER + 10;
    private static final String GLOBAL_SCOPE = "*";

    private final RateLimiters limiters;
    private final RouteRateLimit global;
    private final int trustedProxies;

    public RateLimitFilter(RateLimiters limiters, RouteRateLimit global, int trustedProxies) {
        this.limiters = limiters;
        this.global = global;
        this.trustedProxies = trustedProxies;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        RouteRateLimit own = route != null
                && route.getMetadata().get(RouteDefinitionMapper.RATE_LIMIT_METADATA_KEY) instanceof RouteRateLimit policy
                ? policy : null;
        if (route == null || (global == null && own == null)) {
            return chain.filter(exchange);
        }
        RateLimiter.Decision tightest = null;
        if (global != null) {
            Optional<RateLimiter.Decision> decision = limiters.tryAcquire(GLOBAL_SCOPE, global,
                    CallerKeys.of(global.key(), exchange, trustedProxies));
            if (decision.isPresent() && !decision.get().allowed()) {
                return refuse(exchange, decision.get());
            }
            tightest = decision.orElse(null);
        }
        if (own != null) {
            Optional<RateLimiter.Decision> decision = limiters.tryAcquire(route.getId(), own,
                    CallerKeys.of(own.key(), exchange, trustedProxies));
            if (decision.isPresent() && !decision.get().allowed()) {
                return refuse(exchange, decision.get());
            }
            if (decision.isPresent() && (tightest == null || decision.get().remaining() < tightest.remaining())) {
                tightest = decision.get();
            }
        }
        if (tightest != null) {
            describe(exchange.getResponse().getHeaders(), tightest);
        }
        return chain.filter(exchange);
    }

    private static Mono<Void> refuse(ServerWebExchange exchange, RateLimiter.Decision decision) {
        exchange.getAttributes().put(AccessAttributes.DECISION, AccessDecision.RATE_LIMITED);
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        describe(response.getHeaders(), decision);
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(seconds(decision.retryAfterNanos())));
        return response.setComplete();
    }

    // IETF draft "RateLimit header fields for HTTP": limit, what is left, and seconds to a full budget.
    private static void describe(HttpHeaders headers, RateLimiter.Decision decision) {
        headers.set("RateLimit-Limit", String.valueOf(decision.limit()));
        headers.set("RateLimit-Remaining", String.valueOf(decision.remaining()));
        headers.set("RateLimit-Reset", String.valueOf(seconds(decision.resetNanos())));
        headers.set("RateLimit-Policy", decision.limit() + ";w=" + Math.max(1, seconds(decision.periodNanos())));
    }

    private static long seconds(long nanos) {
        return (nanos + TimeUnit.SECONDS.toNanos(1) - 1) / TimeUnit.SECONDS.toNanos(1);
    }
}
