package com.example.gateway.security;

import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;

import java.util.Locale;

/**
 * When the gateway answered CORS for a request, the backend's own CORS headers
 * are dropped: browsers refuse a response with {@code Access-Control-Allow-Origin}
 * twice, and many backends send it by default. {@code Access-Control-Expose-Headers}
 * stays: only the backend knows which of its headers a front needs to read.
 */
public class UpstreamCorsHeadersFilter implements HttpHeadersFilter {

    @Override
    public HttpHeaders filter(HttpHeaders input, ServerWebExchange exchange) {
        if (exchange.getResponse().getHeaders().getAccessControlAllowOrigin() == null) {
            return input;
        }
        HttpHeaders kept = new HttpHeaders();
        input.forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("access-control-") || lower.equals("access-control-expose-headers")) {
                kept.addAll(name, values);
            }
        });
        return kept;
    }

    @Override
    public boolean supports(Type type) {
        return type == Type.RESPONSE;
    }
}
