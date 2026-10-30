package com.example.gateway.access;

import com.example.gateway.routing.RouteDefinitionMapper;
import com.example.shared.routes.RouteAccess;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Enforces the matched route's access policy before any route filter runs
 * and before the request is forwarded. The policy travels as route metadata
 * (see {@link RouteDefinitionMapper#ACCESS_METADATA_KEY}), so routes stay on
 * the fast compile path. The caller's identity was resolved earlier by the
 * security layer and left in {@link AccessAttributes#IDENTITY}.
 */
public class RouteAccessFilter implements GlobalFilter, Ordered {

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 50;

    private final AccessSettings settings;
    private final UnauthenticatedHandler unauthenticatedHandler;

    public RouteAccessFilter(AccessSettings settings, UnauthenticatedHandler unauthenticatedHandler) {
        this.settings = settings;
        this.unauthenticatedHandler = unauthenticatedHandler;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) {
            return chain.filter(exchange);
        }
        RouteAccess declared = route.getMetadata().get(RouteDefinitionMapper.ACCESS_METADATA_KEY) instanceof RouteAccess access
                ? access
                : null;
        AccessPolicy policy = AccessPolicy.of(route.getId(), declared, settings.defaultMode());
        exchange.getAttributes().put(AccessAttributes.POLICY, policy);
        GatewayIdentity identity = exchange.getAttribute(AccessAttributes.IDENTITY);

        if (!policy.requiresIdentity()) {
            return allow(exchange, chain, identity, policy);
        }
        if (identity == null) {
            return rejectWithoutIdentity(exchange);
        }
        if (!policy.anyOf().isEmpty()) {
            String matched = policy.anyOf().stream().filter(identity::hasAuthority).findFirst().orElse(null);
            if (matched == null) {
                return reject(exchange, AccessDecision.FORBIDDEN, HttpStatus.FORBIDDEN);
            }
            exchange.getAttributes().put(AccessAttributes.MATCHED_AUTHORITY, matched);
        }
        return allow(exchange, chain, identity, policy);
    }

    private Mono<Void> rejectWithoutIdentity(ServerWebExchange exchange) {
        AccessProblem problem = exchange.getAttribute(AccessAttributes.PROBLEM);
        if (problem == AccessProblem.PROVIDER_UNAVAILABLE) {
            // Redirecting to a login that depends on the same unavailable
            // provider would only start a loop.
            return reject(exchange, AccessDecision.ERROR, HttpStatus.SERVICE_UNAVAILABLE);
        }
        AccessDecision decision = switch (problem) {
            case INVALID_TOKEN -> AccessDecision.INVALID_TOKEN;
            case SESSION_EXPIRED -> AccessDecision.SESSION_EXPIRED;
            case null, default -> AccessDecision.UNAUTHENTICATED;
        };
        exchange.getAttributes().put(AccessAttributes.DECISION, decision);
        return unauthenticatedHandler.handle(exchange, decision);
    }

    private static Mono<Void> reject(ServerWebExchange exchange, AccessDecision decision, HttpStatus status) {
        exchange.getAttributes().put(AccessAttributes.DECISION, decision);
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }

    private Mono<Void> allow(ServerWebExchange exchange, GatewayFilterChain chain,
                             GatewayIdentity identity, AccessPolicy policy) {
        exchange.getAttributes().put(AccessAttributes.DECISION, AccessDecision.ALLOWED);
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    IdentityHeaders.strip(headers, settings.identityHeaderPrefix());
                    IdentityHeaders.stripCookies(headers, settings.gatewayCookieNames());
                    if (identity == null) {
                        return;
                    }
                    IdentityHeaders.inject(headers, identity, settings.identityHeaderPrefix(),
                            settings.identityHeaderMaxLength());
                    if (policy.forwardToken() && identity.accessToken() != null) {
                        headers.setBearerAuth(identity.accessToken());
                    } else if (identity.method() == GatewayIdentity.AuthMethod.BEARER) {
                        // The token was meant for the gateway; the backend gets the
                        // identity headers instead unless the route asks for it.
                        headers.remove(HttpHeaders.AUTHORIZATION);
                    }
                })
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }
}
