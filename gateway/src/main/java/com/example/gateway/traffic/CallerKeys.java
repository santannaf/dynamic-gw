package com.example.gateway.traffic;

import com.example.gateway.access.AccessAttributes;
import com.example.gateway.access.ClientAddress;
import com.example.gateway.access.GatewayIdentity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Whose budget a request spends, from the policy's {@code key}. */
final class CallerKeys {

    private static final String HEADER_PREFIX = "header:";
    private static final int MAX_KEY_LENGTH = 128;

    private CallerKeys() {
    }

    /**
     * {@code null} for route-wide budgets. A caller the key cannot name (no
     * identity, no header) falls back to its address, so it never shares one
     * budget with every other anonymous caller.
     */
    static String of(String key, ServerWebExchange exchange, int trustedProxies) {
        if (key == null || "route".equals(key)) {
            return null;
        }
        GatewayIdentity identity = exchange.getAttribute(AccessAttributes.IDENTITY);
        String value = switch (key) {
            case "ip" -> null;
            case "user" -> identity == null ? null : prefixed("user:", identity.subject());
            case "client-id" -> identity == null ? null : prefixed("client:", identity.clientId());
            default -> key.startsWith(HEADER_PREFIX)
                    ? digested("header:", exchange.getRequest().getHeaders().getFirst(key.substring(HEADER_PREFIX.length())))
                    : null;
        };
        return value != null ? value : "ip:" + clientIp(exchange.getRequest(), trustedProxies);
    }

    /**
     * Each trusted proxy appends the address it saw to X-Forwarded-For, so the
     * caller is the entry {@code trustedProxies} positions from the end; the
     * entries before it are whatever the caller sent.
     */
    static String clientIp(ServerHttpRequest request, int trustedProxies) {
        String forwarded = ClientAddress.forwardedFor(request);
        if (trustedProxies > 0 && forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            return hops[Math.max(0, hops.length - trustedProxies)].trim();
        }
        String peer = ClientAddress.peer(request);
        return peer != null ? peer : "unknown";
    }

    private static String prefixed(String prefix, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return prefix + (value.length() <= MAX_KEY_LENGTH ? value : sha256(value));
    }

    // A header key is often an API key: only its digest stays in memory.
    private static String digested(String prefix, String value) {
        return value == null || value.isBlank() ? null : prefix + sha256(value);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
