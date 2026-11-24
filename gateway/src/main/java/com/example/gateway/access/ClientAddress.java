package com.example.gateway.access;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import reactor.netty.http.server.HttpServerRequest;

import java.net.InetSocketAddress;

/**
 * Client address helpers. {@code X-Forwarded-For} is set by the edge
 * (Traefik → nginx-lb) but a client can pre-fill it, so its first entry is
 * only a best-effort hint: audit keeps the raw header next to the TCP peer,
 * and login throttling also counts per username, which cannot be spoofed.
 */
public final class ClientAddress {

    public static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final String FORWARDED = "Forwarded";

    private ClientAddress() {
    }

    /**
     * Whether a proxy handed the request over. The edge always adds
     * {@code X-Forwarded-For}, and a client cannot take out what a proxy
     * appended: a request without it, or without {@code Forwarded}, reached the
     * pod directly, from inside the cluster.
     */
    public static boolean viaProxy(ServerHttpRequest request) {
        return asArrived(request, FORWARDED_FOR) != null || asArrived(request, FORWARDED) != null;
    }

    public static String peer(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null) {
            return null;
        }
        return remote.getAddress() != null ? remote.getAddress().getHostAddress() : remote.getHostString();
    }

    public static String forwardedFor(ServerHttpRequest request) {
        return asArrived(request, FORWARDED_FOR);
    }

    // With server.forward-headers-strategy=framework, Spring takes the forwarded headers out of the
    // request before any filter runs, but only from its own copy: the server's request still has them.
    private static String asArrived(ServerHttpRequest request, String header) {
        String value = request.getHeaders().getFirst(header);
        return value != null ? value : serverHeader(request, header);
    }

    private static String serverHeader(ServerHttpRequest request, String header) {
        try {
            return ServerHttpRequestDecorator.getNativeRequest(request) instanceof HttpServerRequest server
                    ? server.requestHeaders().get(header) : null;
        } catch (IllegalStateException | IllegalArgumentException noServer) {
            // A request that no server received (MockServerHttpRequest) has only its own headers.
            return null;
        }
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
