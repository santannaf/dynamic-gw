package com.example.gateway.access;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * Tells a top-level browser navigation (which can follow a redirect to the
 * login page) apart from API and XHR calls (which get a 401). Requires an
 * explicit {@code text/html} in {@code Accept}: curl and most API clients
 * send {@code *}{@code /*}, which must not trigger a redirect.
 */
public final class BrowserNavigation {

    private BrowserNavigation() {
    }

    public static boolean matches(ServerHttpRequest request) {
        HttpMethod method = request.getMethod();
        if (!HttpMethod.GET.equals(method) && !HttpMethod.HEAD.equals(method)) {
            return false;
        }
        HttpHeaders headers = request.getHeaders();
        if (headers.containsHeader(HttpHeaders.AUTHORIZATION)) {
            return false;
        }
        if ("XMLHttpRequest".equalsIgnoreCase(headers.getFirst("X-Requested-With"))) {
            return false;
        }
        try {
            return headers.getAccept().stream()
                    .anyMatch(mt -> "text".equals(mt.getType()) && "html".equals(mt.getSubtype()));
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }
}
