package com.example.gateway.security.session;

import com.example.gateway.security.GatewayAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Security context backed by the session cookie instead of a WebSession.
 * {@code save} does nothing: sessions are created by the provider's login
 * success path, which writes the cookie and the cache entry itself.
 * The resolution is memoized per exchange because Spring Security may load
 * the context more than once while handling a request.
 */
public class SessionSecurityContextRepository implements ServerSecurityContextRepository {

    private static final String RESOLVED = SessionSecurityContextRepository.class.getName() + ".resolved";

    private final GatewaySessionManager sessionManager;

    public SessionSecurityContextRepository(GatewaySessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public Mono<Void> save(ServerWebExchange exchange, SecurityContext context) {
        return Mono.empty();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Mono<SecurityContext> load(ServerWebExchange exchange) {
        // Not computeIfAbsent: resolving writes other attributes (PROBLEM) on the
        // same map, which ConcurrentHashMap rejects as a recursive update.
        Object existing = exchange.getAttribute(RESOLVED);
        if (existing != null) {
            return (Mono<SecurityContext>) existing;
        }
        Mono<SecurityContext> resolved = Mono.defer(() -> sessionManager.resolve(exchange))
                .<SecurityContext>map(identity -> new SecurityContextImpl(new GatewayAuthenticationToken(identity)))
                .cache();
        exchange.getAttributes().put(RESOLVED, resolved);
        return resolved;
    }
}
