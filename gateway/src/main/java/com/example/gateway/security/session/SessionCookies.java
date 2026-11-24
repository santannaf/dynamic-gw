package com.example.gateway.security.session;

import com.example.gateway.security.GatewaySecurityProperties;
import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseCookie;
import org.springframework.web.server.ServerWebExchange;

import java.time.Duration;
import java.util.Optional;

/** Reads and writes the gateway's cookies with one set of attributes. */
public class SessionCookies {

    private final boolean secure;

    public SessionCookies(GatewaySecurityProperties.Session session) {
        this.secure = session.isCookieSecure();
    }

    public Optional<String> read(ServerWebExchange exchange, String name) {
        HttpCookie cookie = exchange.getRequest().getCookies().getFirst(name);
        return cookie == null ? Optional.empty() : Optional.of(cookie.getValue());
    }

    /** Session cookie: lives until the browser closes; validity is enforced server-side. */
    public void write(ServerWebExchange exchange, String name, String value, String path) {
        exchange.getResponse().addCookie(base(name, value, path).build());
    }

    public void write(ServerWebExchange exchange, String name, String value, String path, Duration maxAge) {
        exchange.getResponse().addCookie(base(name, value, path).maxAge(maxAge).build());
    }

    public void expire(ServerWebExchange exchange, String name, String path) {
        exchange.getResponse().addCookie(base(name, "", path).maxAge(Duration.ZERO).build());
    }

    private ResponseCookie.ResponseCookieBuilder base(String name, String value, String path) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(path);
    }
}
