package com.example.gateway.access;

/**
 * Exchange attribute names shared by the security layer, the route access
 * filter and the audit filter. Spring Security decorates the exchange, but
 * the decorated exchange shares the attribute map with the original one, so
 * the outer audit filter reads what the inner filters wrote.
 */
public final class AccessAttributes {

    /** {@link GatewayIdentity} of the caller, when one was resolved. */
    public static final String IDENTITY = AccessAttributes.class.getName() + ".identity";
    /** {@link AccessProblem} explaining why no identity is present, when that matters. */
    public static final String PROBLEM = AccessAttributes.class.getName() + ".problem";
    /** {@link AccessPolicy} applied to the matched route. */
    public static final String POLICY = AccessAttributes.class.getName() + ".policy";
    /** {@link AccessDecision} taken for this exchange. */
    public static final String DECISION = AccessAttributes.class.getName() + ".decision";
    /** Authority from {@code anyOf} that granted access. */
    public static final String MATCHED_AUTHORITY = AccessAttributes.class.getName() + ".matchedAuthority";
    /** Audit action set by gateway-owned endpoints (login, logout, whoami). */
    public static final String AUDIT_ACTION = AccessAttributes.class.getName() + ".auditAction";
    /** Username typed in a failed form login; audited, never the password. */
    public static final String ATTEMPTED_USERNAME = AccessAttributes.class.getName() + ".attemptedUsername";

    private AccessAttributes() {
    }
}
