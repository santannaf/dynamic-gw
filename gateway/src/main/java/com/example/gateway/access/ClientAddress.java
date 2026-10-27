package com.example.gateway.access;

import org.springframework.http.server.reactive.ServerHttpRequest;

import java.net.InetSocketAddress;

/**
 * Client address helpers. {@code X-Forwarded-For} is set by the edge
 * (Traefik → nginx-lb) but a client can pre-fill it, so its first entry is
 * only a best-effort hint: audit keeps the raw header next to the TCP peer,
 * and login throttling also counts per username, which cannot be spoofed.
 */
public final class ClientAddress {

    public static final String FORWARDED_FOR = "X-Forwarded-For";

    private ClientAddress() {
    }

    public static String peer(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null) {
            return null;
        }
        return remote.getAddress() != null ? remote.getAddress().getHostAddress() : remote.getHostString();
    }

    public static String forwardedFor(ServerHttpRequest request) {
        return request.getHeaders().getFirst(FORWARDED_FOR);
    }

    /** First {@code X-Forwarded-For} entry, or the TCP peer when the header is absent. */
    public static String bestEffortClient(ServerHttpRequest request) {
        String forwarded = forwardedFor(request);
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
        }
        return peer(request);
    }
}
