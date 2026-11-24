package com.example.gateway.audit;

import java.time.Instant;
import java.util.List;

/**
 * One audited request: who, what, when and how it ended. Field names map to
 * the Elastic Common Schema when written (see {@link LogAuditEventSink}).
 * Never holds credentials: no tokens, cookies, Authorization values, bodies,
 * and the query string only when explicitly enabled.
 */
public record AuditEvent(
        Instant timestamp,
        long durationNanos,
        String action,
        boolean allowed,
        String reason,
        // who
        String userId,
        String userName,
        String userEmail,
        String authMethod,
        String clientId,
        String sessionId,
        // what
        String routeId,
        String routeTeam,
        String accessMode,
        List<String> requiredAuthorities,
        String matchedAuthority,
        String upstreamHost,
        String method,
        String path,
        String query,
        String requestId,
        Integer status,
        boolean cancelled,
        // where from
        String sourceIp,
        String forwardedFor,
        String userAgent
) {
}
