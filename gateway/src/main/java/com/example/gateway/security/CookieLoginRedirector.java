package com.example.gateway.security;

import com.example.gateway.access.LoginRedirector;
import org.springframework.security.web.server.DefaultServerRedirectStrategy;
import org.springframework.security.web.server.ServerRedirectStrategy;
import org.springframework.security.web.server.savedrequest.ServerRequestCache;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;

/** Saves the current URL in the cookie request cache and redirects to the provider's login. */
public class CookieLoginRedirector implements LoginRedirector {

    private final ServerRequestCache requestCache;
    private final URI loginUri;
    private final ServerRedirectStrategy redirectStrategy = new DefaultServerRedirectStrategy();

    public CookieLoginRedirector(ServerRequestCache requestCache, String loginPath) {
        this.requestCache = requestCache;
        this.loginUri = URI.create(loginPath);
    }

    @Override
    public Mono<Void> redirectToLogin(ServerWebExchange exchange) {
        return requestCache.saveRequest(exchange)
                .then(redirectStrategy.sendRedirect(exchange, loginUri));
    }
}
