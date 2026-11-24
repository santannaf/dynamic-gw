package com.example.gateway.security;

import com.example.gateway.access.GatewayIdentity;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * The main channel's security context and, on {@code GET /internal/routes}, the
 * caller of another channel when the main one has none: whoever operates the
 * gateway ({@code gateway.security.internal.any-of}) comes in through any of its
 * channels. Routes never get this fallback; their channel decides who calls.
 */
final class OperatorSecurityContextRepository implements ServerSecurityContextRepository {

    private static final ServerWebExchangeMatcher INTERNAL_ROUTES =
            new PathPatternParserServerWebExchangeMatcher(SecurityChains.INTERNAL_ROUTES_PATH, HttpMethod.GET);

    private final ServerSecurityContextRepository main;
    private final Function<ServerWebExchange, Mono<GatewayIdentity>> otherChannels;

    OperatorSecurityContextRepository(ServerSecurityContextRepository main,
                                      Function<ServerWebExchange, Mono<GatewayIdentity>> otherChannels) {
        this.main = main;
        this.otherChannels = otherChannels;
    }

    @Override
    public Mono<Void> save(ServerWebExchange exchange, SecurityContext context) {
        return main.save(exchange, context);
    }

    @Override
    public Mono<SecurityContext> load(ServerWebExchange exchange) {
        return INTERNAL_ROUTES.matches(exchange).flatMap(match -> match.isMatch()
                ? main.load(exchange).switchIfEmpty(Mono.defer(() -> otherChannels.apply(exchange))
                        .map(identity -> new SecurityContextImpl(new GatewayAuthenticationToken(identity))))
                : main.load(exchange));
    }
}
