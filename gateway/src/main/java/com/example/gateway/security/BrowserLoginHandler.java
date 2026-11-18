package com.example.gateway.security;

import com.example.gateway.access.AccessAttributes;
import com.example.gateway.access.AccessDecision;
import com.example.gateway.access.AccessPolicy;
import com.example.gateway.access.BrowserNavigation;
import com.example.gateway.access.UnauthenticatedHandler;
import com.example.gateway.security.session.BrowserSession;
import com.example.shared.routes.RoutePolicyRules;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.server.DefaultServerRedirectStrategy;
import org.springframework.security.web.server.ServerRedirectStrategy;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Optional;

/**
 * Sends a browser navigation to the login, saving the current URL so the user
 * lands back on it. API and XHR calls cannot follow that redirect and go to
 * {@code apiCalls}. On a route with {@code access.identityProvider}, the login
 * goes through that provider ({@code ?idp=} on the login URL).
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
            String identityProvider = requiredIdentityProvider(exchange);
            if (identityProvider != null) {
                if (session.identityProviderLoginTried(exchange, identityProvider)) {
                    exchange.getAttributes().put(AccessAttributes.DECISION, AccessDecision.WRONG_IDENTITY_PROVIDER);
                    exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                    return exchange.getResponse().setComplete();
                }
                session.rememberIdentityProviderLogin(exchange, identityProvider);
            }
            session.forgetReturnTarget(exchange);
            return session.requestCache().saveRequest(exchange)
                    .then(redirectStrategy.sendRedirect(exchange, loginUri(identityProvider)));
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
        return startLogin(exchange, returnTo, null);
    }

    /** As {@link #startLogin(ServerWebExchange, String)}, through {@code identityProvider} when given. */
    public Optional<URI> startLogin(ServerWebExchange exchange, String returnTo, String identityProvider) {
        if (identityProvider != null && !RoutePolicyRules.isIdentityProviderAlias(identityProvider)) {
            return Optional.empty();
        }
        return session.returnTargets().parse(returnTo).map(target -> {
            session.saveReturnTarget(exchange, target);
            return loginUri(identityProvider);
        });
    }

    private URI loginUri(String identityProvider) {
        return identityProvider == null ? loginUri : URI.create(loginUri + "?idp=" + identityProvider);
    }

    private static String requiredIdentityProvider(ServerWebExchange exchange) {
        AccessPolicy policy = exchange.getAttribute(AccessAttributes.POLICY);
        return policy == null ? null : policy.identityProvider();
    }
}
