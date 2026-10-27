package com.example.gateway.security.keycloak;

import com.example.gateway.access.GatewayIdentity;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns access token claims into normalized authorities. Claim locations are
 * dot-separated paths ({@code realm_access.roles}); {@code {client-id}} in a
 * path is replaced by the configured client id. Where the groups come from
 * (Keycloak groups or AD groups federated through LDAP) is invisible here:
 * both arrive in the same claim.
 */
public class ClaimAuthorities {

    private final List<String[]> groupPaths;
    private final List<String[]> rolePaths;

    public ClaimAuthorities(List<String> groupClaims, List<String> roleClaims, String clientId) {
        this.groupPaths = split(groupClaims, clientId);
        this.rolePaths = split(roleClaims, clientId);
    }

    public Set<String> from(Map<String, Object> claims) {
        Set<String> authorities = new LinkedHashSet<>();
        for (String[] path : groupPaths) {
            valuesAt(claims, path).forEach(v -> authorities.add(GatewayIdentity.GROUP_PREFIX + v));
        }
        for (String[] path : rolePaths) {
            valuesAt(claims, path).forEach(v -> authorities.add(GatewayIdentity.ROLE_PREFIX + v));
        }
        return authorities;
    }

    private static List<String[]> split(List<String> paths, String clientId) {
        return paths.stream()
                .map(p -> p.replace("{client-id}", clientId == null ? "" : clientId))
                .map(p -> p.split("\\."))
                .toList();
    }

    private static List<String> valuesAt(Map<String, Object> claims, String[] path) {
        Object current = claims;
        for (String segment : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return List.of();
            }
            current = map.get(segment);
        }
        if (current instanceof Collection<?> values) {
            return values.stream().filter(v -> v != null && !v.toString().isBlank()).map(Object::toString).toList();
        }
        if (current instanceof String value && !value.isBlank()) {
            return List.of(value);
        }
        return List.of();
    }
}
