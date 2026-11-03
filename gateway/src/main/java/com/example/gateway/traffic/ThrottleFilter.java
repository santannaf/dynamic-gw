package com.example.gateway.traffic;

import com.example.gateway.access.AccessAttributes;
import com.example.gateway.access.AccessDecision;
import com.example.gateway.routing.RouteDefinitionMapper;
import com.example.shared.routes.RouteThrottle;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Concurrency caps: the global one ({@code gateway.traffic.throttle}) and the
 * route's {@code throttle}. A request holds one slot of each while the gateway
 * serves it, waiting in the queue when they are all taken; a full queue or an
 * expired wait answers 503 with {@code Retry-After}.
 */
public class ThrottleFilter implements GlobalFilter, Ordered {

    public static final int ORDER = RateLimitFilter.ORDER + 10;
    private static final int SWEEP_THRESHOLD = 10_000;

    private final ConcurrencyLimiter global;
    private final ConcurrentHashMap<Key, ConcurrencyLimiter> routes = new ConcurrentHashMap<>();

    public ThrottleFilter(RouteThrottle global) {
        this.global = global == null ? null : new ConcurrencyLimiter(global);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        RouteThrottle own = route != null
                && route.getMetadata().get(RouteDefinitionMapper.THROTTLE_METADATA_KEY) instanceof RouteThrottle policy
                ? policy : null;
        if (route == null || (global == null && own == null)) {
            return chain.filter(exchange);
        }
        Mono<Void> call = Mono.defer(() -> chain.filter(exchange));
        if (own != null) {
            call = holding(limiterOf(route.getId(), own), call);
        }
        if (global != null) {
            call = holding(global, call);
        }
        return call.onErrorResume(ConcurrencyLimiter.Rejected.class, rejected -> refuse(exchange));
    }

    private static Mono<Void> holding(ConcurrencyLimiter limiter, Mono<Void> call) {
        return Mono.usingWhen(limiter.acquire(),
                permit -> call,
                permit -> Mono.fromRunnable(permit::release),
                (permit, error) -> Mono.fromRunnable(permit::release),
                permit -> Mono.fromRunnable(permit::release));
    }

    // A route that changes its throttle gets a new limiter; idle ones of old policies are dropped.
    private ConcurrencyLimiter limiterOf(String routeId, RouteThrottle policy) {
        if (routes.size() > SWEEP_THRESHOLD) {
            routes.values().removeIf(limiter -> limiter.inFlight() == 0 && limiter.waiting() == 0);
        }
        return routes.computeIfAbsent(new Key(routeId, policy), key -> new ConcurrencyLimiter(policy));
    }

    private static Mono<Void> refuse(ServerWebExchange exchange) {
        exchange.getAttributes().put(AccessAttributes.DECISION, AccessDecision.THROTTLED);
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
        return PolicyErrors.write(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                "Too many requests in progress, and the queue is full or the wait ran out. Retry after 1s.");
    }

    private record Key(String routeId, RouteThrottle policy) {
    }
}
