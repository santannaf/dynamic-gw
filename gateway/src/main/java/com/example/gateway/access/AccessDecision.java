package com.example.gateway.access;

/**
 * Outcome of an access check. {@code auditReason} is the value written to
 * {@code event.reason}; {@code allowed} drives {@code event.outcome}.
 */
public enum AccessDecision {
    ALLOWED("allowed", true),
    UNAUTHENTICATED("unauthenticated", false),
    FORBIDDEN("forbidden", false),
    WRONG_IDENTITY_PROVIDER("wrong_identity_provider", false),
    TOKEN_EXCHANGE_FAILED("token_exchange_failed", false),
    IDENTITY_PROVIDER_LOGIN_REQUIRED("identity_provider_login_required", false),
    INVALID_TOKEN("invalid_token", false),
    SESSION_EXPIRED("session_expired", false),
    THROTTLED("throttled", false),
    RATE_LIMITED("rate_limited", false),
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
