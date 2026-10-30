package com.example.gateway.audit;

import com.example.gateway.access.AccessAttributes;
import com.example.gateway.access.AccessDecision;
import com.example.gateway.access.AccessPolicy;
import com.example.gateway.access.AccessProblem;
import com.example.gateway.access.ClientAddress;
import com.example.gateway.access.GatewayIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;

/**
 * Emits exactly one audit event per request, when the exchange terminates
 * (complete, error or client cancel). Runs before Spring Security's chain so
 * that 401s produced inside it are audited too; everything about the caller
 * and the decision is read from exchange attributes written by inner filters,
 * because the decorated exchange shares the attribute map with this one.
 */
public class AccessAuditWebFilter implements WebFilter, Ordered {

    public static final int ORDER = -200;

    private static final Logger log = LoggerFactory.getLogger(AccessAuditWebFilter.class);

    private final AuditEventSink sink;
    private final boolean includeQuery;
    private final Clock clock;

    public AccessAuditWebFilter(AuditEventSink sink, boolean includeQuery, Clock clock) {
        this.sink = sink;
        this.includeQuery = includeQuery;
        this.clock = clock;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/actuator/health")) {
            return chain.filter(exchange);
        }
        Instant start = clock.instant();
        long startNanos = System.nanoTime();
        return chain.filter(exchange)
                .doFinally(signal -> emitSafely(exchange, start, System.nanoTime() - startNanos, signal));
    }

    private void emitSafely(ServerWebExchange exchange, Instant start, long durationNanos, SignalType signal) {
        boolean routed = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR) != null;
        if (CorsUtils.isPreFlightRequest(exchange.getRequest()) && !routed) {
            return;
        }
        try {
            sink.emit(build(exchange, start, durationNanos, signal == SignalType.CANCEL));
        } catch (RuntimeException e) {
            // Audit must never break the request, but losing an event is worth a loud log line.
            log.error("Failed to emit audit event for {} {}: {}", exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath().value(), e.toString());
        }
    }

    AuditEvent build(ServerWebExchange exchange, Instant start, long durationNanos, boolean cancelled) {
        ServerHttpRequest request = exchange.getRequest();
        HttpStatusCode statusCode = exchange.getResponse().getStatusCode();
        Integer status = statusCode == null ? null : statusCode.value();
        GatewayIdentity identity = exchange.getAttribute(AccessAttributes.IDENTITY);
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        AccessPolicy policy = exchange.getAttribute(AccessAttributes.POLICY);
        AccessDecision decision = decision(exchange, status, route);
        String userName = identity != null ? identity.username() : exchange.getAttribute(AccessAttributes.ATTEMPTED_USERNAME);

        return new AuditEvent(
                start,
                durationNanos,
                action(exchange, request, route),
                decision.allowed(),
                decision.auditReason(),
                identity == null ? null : identity.subject(),
                userName == null || userName.isBlank() ? null : userName,
                identity == null ? null : identity.email(),
                identity == null ? "none" : identity.method().auditValue(),
                identity == null ? null : identity.clientId(),
                identity == null ? null : identity.sessionId(),
                route == null ? null : route.getId(),
                route == null ? null : stringOrNull(route.getMetadata().get("team")),
                policy == null ? null : policy.mode().value(),
                policy == null ? null : policy.anyOf(),
                exchange.getAttribute(AccessAttributes.MATCHED_AUTHORITY),
                route == null ? null : upstreamHost(route.getUri()),
                request.getMethod().name(),
                request.getPath().value(),
                includeQuery ? request.getURI().getRawQuery() : null,
                request.getId(),
                status,
                cancelled,
                ClientAddress.peer(request),
                ClientAddress.forwardedFor(request),
                request.getHeaders().getFirst(HttpHeaders.USER_AGENT));
    }

    private static String action(ServerWebExchange exchange, ServerHttpRequest request, Route route) {
        String explicit = exchange.getAttribute(AccessAttributes.AUDIT_ACTION);
        if (explicit != null) {
            return explicit;
        }
        String path = request.getPath().value();
        if (path.startsWith("/_gateway/oauth2/authorization/")) {
            return "auth.login_start";
        }
        if (path.startsWith("/_gateway/login")) {
            return "auth.login";
        }
        if (path.equals("/internal/routes/reload") && HttpMethod.POST.equals(request.getMethod())) {
            return "internal.routes.reload";
        }
        if (path.startsWith("/internal/routes")) {
            return "internal.routes.list";
        }
        return route != null ? "route.access" : "request.unmatched";
    }

    /**
     * The route filter and the login handlers record their decision; for the
     * rest (gateway endpoints, 401s from Spring Security, unmatched paths)
     * the status code tells the story. A 400 outside a route is a refusal of
     * the gateway itself: the request firewall or a bad {@code returnTo}.
     */
    private static AccessDecision decision(ServerWebExchange exchange, Integer status, Route route) {
        AccessDecision recorded = exchange.getAttribute(AccessAttributes.DECISION);
        if (recorded != null) {
            return recorded;
        }
        AccessProblem problem = exchange.getAttribute(AccessAttributes.PROBLEM);
        if (status == null) {
            return AccessDecision.ALLOWED;
        }
        return switch (status) {
            case 400 -> route == null ? AccessDecision.FORBIDDEN : AccessDecision.ALLOWED;
            case 401 -> problem == AccessProblem.INVALID_TOKEN ? AccessDecision.INVALID_TOKEN
                    : problem == AccessProblem.SESSION_EXPIRED ? AccessDecision.SESSION_EXPIRED
                    : AccessDecision.UNAUTHENTICATED;
            case 403 -> AccessDecision.FORBIDDEN;
            case 404 -> route == null ? AccessDecision.NO_ROUTE : AccessDecision.ALLOWED;
            case 429 -> AccessDecision.THROTTLED;
            default -> status >= 500 && route == null ? AccessDecision.ERROR : AccessDecision.ALLOWED;
        };
    }

    private static String upstreamHost(URI uri) {
        if (uri == null || uri.getHost() == null) {
            return null;
        }
        return uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }
}
