package com.example.gateway.access;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Sends a browser without identity to the active provider's login, saving
 * the current URL so the user lands back on it. Implemented by the security
 * layer; the route filter only knows this port.
 */
public interface LoginRedirector {

    Mono<Void> redirectToLogin(ServerWebExchange exchange);
}
