package com.example.gateway.security;

import com.example.gateway.access.AccessDecision;
import com.example.gateway.access.BrowserNavigation;
import com.example.gateway.access.UnauthenticatedHandler;
import com.example.gateway.security.session.BrowserSession;
import org.springframework.security.web.server.DefaultServerRedirectStrategy;
import org.springframework.security.web.server.ServerRedirectStrategy;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Optional;

/**
 * Sends a browser navigation to the login, saving the current URL so the user
 * lands back on it. API and XHR calls cannot follow that redirect and go to
 * {@code apiCalls}.
 */
public class BrowserLoginHandler implements UnauthenticatedHandler {

    private final BrowserSession session;
    private final URI loginUri;
    private final UnauthenticatedHandler apiCalls;
    private final ServerRedirectStrategy redirectStrategy = new DefaultServerRedirectStrategy();

    public BrowserLoginHandler(BrowserSession session, String loginPath, UnauthenticatedHandler apiCalls) {
        this.session = session;
        this.loginUri = URI.create(loginPath);
        this.apiCalls = apiCalls;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDecision decision) {
        if (BrowserNavigation.matches(exchange.getRequest())) {
            session.forgetReturnTarget(exchange);
            return session.requestCache().saveRequest(exchange)
                    .then(redirectStrategy.sendRedirect(exchange, loginUri));
        }
        return apiCalls.handle(exchange, decision);
    }

    /**
     * For a page the gateway does not serve, such as a static front on a CDN:
     * it only gets a 401 from its AJAX calls, so it sends the browser to the
     * login itself and asks to come back.
     *
     * @return the login to redirect to; empty when {@code returnTo} is not allowed
     */
    public Optional<URI> startLogin(ServerWebExchange exchange, String returnTo) {
        return session.returnTargets().parse(returnTo).map(target -> {
            session.saveReturnTarget(exchange, target);
            return loginUri;
        });
    }
}
