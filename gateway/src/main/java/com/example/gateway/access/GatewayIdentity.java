package com.example.gateway.access;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Who is calling, independent of the identity provider (Keycloak or LDAP) and
 * of how the caller authenticated (session cookie or bearer token).
 *
 * {@code authorities} use the normalized form {@code <type>:<name>} that routes
 * declare in {@code access.anyOf}; each identity provider decides which types
 * it produces.
 * {@code accessToken} is only present for Keycloak identities and is kept out
 * of {@link #toString()} so it never reaches a log line by accident.
 */
public record GatewayIdentity(
        String subject,
        String username,
        String email,
        String sessionId,
        String clientId,
        Set<String> authorities,
        AuthMethod method,
        String accessToken,
        Instant expiresAt
) {
    public static final String GROUP_PREFIX = "group:";
    public static final String ROLE_PREFIX = "role:";

    public GatewayIdentity {
        authorities = authorities == null ? Set.of() : Set.copyOf(authorities);
    }

    public boolean hasAuthority(String authority) {
        return authorities.contains(authority);
    }

    public List<String> groups() {
        return namesWithPrefix(GROUP_PREFIX);
    }

    public List<String> roles() {
        return namesWithPrefix(ROLE_PREFIX);
    }

    private List<String> namesWithPrefix(String prefix) {
        return authorities.stream()
                .filter(a -> a.startsWith(prefix))
                .map(a -> a.substring(prefix.length()))
                .sorted()
                .toList();
    }

    @Override
    public String toString() {
        return "GatewayIdentity[subject=" + subject + ", username=" + username + ", sessionId=" + sessionId
                + ", clientId=" + clientId + ", method=" + method + ", authorities=" + authorities + "]";
    }

    public enum AuthMethod {
        SESSION("session"),
        BEARER("bearer");

        private final String auditValue;

        AuthMethod(String auditValue) {
            this.auditValue = auditValue;
        }

        public String auditValue() {
            return auditValue;
        }
    }
}
