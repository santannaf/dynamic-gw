package com.example.gateway.access;

/**
 * Outcome of an access check. {@code auditReason} is the value written to
 * {@code event.reason}; {@code allowed} drives {@code event.outcome}.
 */
public enum AccessDecision {
    ALLOWED("allowed", true),
    UNAUTHENTICATED("unauthenticated", false),
    FORBIDDEN("forbidden", false),
    INVALID_TOKEN("invalid_token", false),
    SESSION_EXPIRED("session_expired", false),
    THROTTLED("throttled", false),
    NO_ROUTE("no_route", false),
    ERROR("error", false);

    private final String auditReason;
    private final boolean allowed;

    AccessDecision(String auditReason, boolean allowed) {
        this.auditReason = auditReason;
        this.allowed = allowed;
    }

    public String auditReason() {
        return auditReason;
    }

    public boolean allowed() {
        return allowed;
    }
}
