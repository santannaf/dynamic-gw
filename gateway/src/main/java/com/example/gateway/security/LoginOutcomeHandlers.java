package com.example.gateway.security;

import com.example.gateway.access.AccessAttributes;
import com.example.gateway.access.AccessDecision;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.server.DefaultServerRedirectStrategy;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.savedrequest.ServerRequestCache;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/** Login success/failure handling shared by both providers, with the audit markers. */
public final class LoginOutcomeHandlers {

    public static final String LOGIN_ACTION = "auth.login";
    public static final String LOGIN_START_ACTION = "auth.login_start";

    private LoginOutcomeHandlers() {
    }

    /** Marks the audit event and sends the browser back to the URL saved before login (or {@code /}). */
    public static ServerAuthenticationSuccessHandler success(ServerRequestCache requestCache) {
        DefaultServerRedirectStrategy redirects = new DefaultServerRedirectStrategy();
        return (webFilterExchange, authentication) -> {
            ServerWebExchange exchange = webFilterExchange.getExchange();
            mark(exchange, AccessDecision.ALLOWED);
            return requestCache.getRedirectUri(exchange)
                    .defaultIfEmpty(URI.create("/"))
                    // removeMatchingRequest expires the saved-request cookie
                    .flatMap(target -> requestCache.removeMatchingRequest(exchange)
                            .then(redirects.sendRedirect(exchange, target)));
        };
    }

    /** OIDC callback failure (bad state, refused code, invalid ID token). */
    public static ServerAuthenticationFailureHandler oidcFailure() {
        return (webFilterExchange, exception) -> {
            mark(webFilterExchange.getExchange(), AccessDecision.UNAUTHENTICATED);
            return plain(webFilterExchange, HttpStatus.UNAUTHORIZED, "Login failed.");
        };
    }

    public static void mark(ServerWebExchange exchange, AccessDecision decision) {
        exchange.getAttributes().put(AccessAttributes.AUDIT_ACTION, LOGIN_ACTION);
        exchange.getAttributes().put(AccessAttributes.DECISION, decision);
    }

    public static Mono<Void> plain(WebFilterExchange webFilterExchange, HttpStatus status, String message) {
        var response = webFilterExchange.getExchange().getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8));
        response.getHeaders().setCacheControl("no-store");
        return response.writeWith(Mono.just(response.bufferFactory().wrap((message + "\n").getBytes(StandardCharsets.UTF_8))));
    }
}
