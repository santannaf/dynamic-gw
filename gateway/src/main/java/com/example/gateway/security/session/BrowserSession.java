package com.example.gateway.security.session;

import com.example.gateway.security.GatewaySecurityProperties;
import com.example.gateway.security.ReturnTargets;
import com.example.gateway.security.SecurityChains;
import org.springframework.security.web.server.savedrequest.CookieServerRequestCache;
import org.springframework.security.web.server.savedrequest.ServerRequestCache;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

/** What the channels with a browser session share: the session cookie and the URL saved before login. */
public record BrowserSession(SessionCookieCodec codec, SessionCookies cookies, ServerRequestCache requestCache,
                             ReturnTargets returnTargets, Set<String> cookieNames) {

    /** Name of the cookie {@link CookieServerRequestCache} uses for the URL saved before login. */
    private static final String SAVED_REQUEST_COOKIE = "REDIRECT_URI";
    // CookieServerRequestCache only reads relative paths back, and a front on
    // another origin needs an absolute URL.
    private static final String RETURN_TARGET_COOKIE = "GW_RETURN_TO";
    private static final URI DEFAULT_TARGET = URI.create("/");

    public static BrowserSession from(GatewaySecurityProperties properties) {
        SecurityChains.requireText(properties.getPublicBaseUrl(), "gateway.security.public-base-url");
        GatewaySecurityProperties.Session session = properties.getSession();
        CookieServerRequestCache requestCache = new CookieServerRequestCache();
        boolean secure = session.isCookieSecure();
        requestCache.setCookieCustomizer(cookie -> cookie.secure(secure).sameSite("Lax"));
        return new BrowserSession(new SessionCookieCodec(session.getKeys()), new SessionCookies(session), requestCache,
                new ReturnTargets(properties.trustedOrigins()),
                Set.of(session.getCookieName(), session.getAuthzCookieName(), SAVED_REQUEST_COOKIE, RETURN_TARGET_COOKIE));
    }

    public void saveReturnTarget(ServerWebExchange exchange, URI target) {
        byte[] value = target.toString().getBytes(StandardCharsets.UTF_8);
        cookies.write(exchange, RETURN_TARGET_COOKIE, Base64.getUrlEncoder().withoutPadding().encodeToString(value), "/");
    }

    /** A login started by a navigation replaces one a front started and abandoned. */
    public void forgetReturnTarget(ServerWebExchange exchange) {
        if (cookies.read(exchange, RETURN_TARGET_COOKIE).isPresent()) {
            cookies.expire(exchange, RETURN_TARGET_COOKIE, "/");
        }
    }

    /**
     * Where the browser goes after the login: the target a front asked for, the
     * URL saved before the login, or {@code /}. The browser can change the
     * cookie, so the target goes through {@link ReturnTargets} again.
     */
    public Mono<URI> takeReturnTarget(ServerWebExchange exchange) {
        Optional<URI> asked = cookies.read(exchange, RETURN_TARGET_COOKIE).flatMap(this::decodeReturnTarget);
        forgetReturnTarget(exchange);
        return requestCache.getRedirectUri(exchange)
                .defaultIfEmpty(DEFAULT_TARGET)
                .flatMap(saved -> requestCache.removeMatchingRequest(exchange).thenReturn(asked.orElse(saved)));
    }

    private Optional<URI> decodeReturnTarget(String cookie) {
        try {
            return returnTargets.parse(new String(Base64.getUrlDecoder().decode(cookie), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
