package com.example.gateway.security;

import com.example.gateway.access.GatewayIdentity;
import com.example.gateway.security.session.GatewaySessionManager;
import com.example.gateway.security.session.SessionRevocations;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;

/**
 * The sessions of a gateway with more than one channel, which share one
 * {@link SessionRevocations}. The logout ends all of them, and the browser goes
 * where the main channel sends it, unless it only had a session in another one.
 */
final class AllChannelSessions implements GatewaySessionManager {

    private final GatewaySessionManager main;
    private final String mainCookie;
    private final List<GatewaySessionManager> others;

    AllChannelSessions(GatewaySessionManager main, String mainCookie, List<GatewaySessionManager> others) {
        this.main = main;
        this.mainCookie = mainCookie;
        this.others = List.copyOf(others);
    }

    @Override
    public Mono<GatewayIdentity> resolve(ServerWebExchange exchange) {
        return main.resolve(exchange);
    }

    @Override
    public Mono<URI> logout(ServerWebExchange exchange) {
        boolean inMain = exchange.getRequest().getCookies().containsKey(mainCookie);
        return Flux.fromIterable(others)
                .concatMap(other -> other.logout(exchange))
                .collectList()
                .flatMap(targets -> main.logout(exchange)
                        .map(mainTarget -> inMain || targets.isEmpty() ? mainTarget : targets.getFirst()));
    }

    @Override
    public SessionRevocations revocations() {
        return main.revocations();
    }
}
