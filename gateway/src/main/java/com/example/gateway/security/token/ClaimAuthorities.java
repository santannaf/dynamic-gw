package com.example.gateway.security.token;

import com.example.gateway.access.GatewayIdentity;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns access token claims into normalized authorities. Claim locations are
 * dot-separated paths ({@code realm_access.roles}); {@code {client-id}} in a
 * path is replaced by the configured client id. Where the groups come from
 * (Keycloak groups or AD groups federated through LDAP) is invisible here:
 * both arrive in the same claim. Any other claim maps to an authority type of
 * its own through {@code otherClaims}, keyed by type.
 */
public class ClaimAuthorities {

    private static final Pattern AUTHORITY_TYPE = Pattern.compile("^[a-z][a-z0-9-]*$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final List<String[]> groupPaths;
    private final List<String[]> rolePaths;
    private final Map<String, List<String[]>> otherPaths = new LinkedHashMap<>();

    public ClaimAuthorities(List<String> groupClaims, List<String> roleClaims,
                            Map<String, List<String>> otherClaims, String clientId) {
        this.groupPaths = split(groupClaims, clientId);
        this.rolePaths = split(roleClaims, clientId);
        otherClaims.forEach((type, paths) -> {
            if (type.equals("group") || type.equals("role")) {
                throw new IllegalStateException("gateway.security.authorities.claims key '" + type
                        + "' has its own setting: gateway.security.authorities." + type + "-claims");
            }
            if (!AUTHORITY_TYPE.matcher(type).matches()) {
                throw new IllegalStateException("gateway.security.authorities.claims key '" + type
                        + "' must match " + AUTHORITY_TYPE.pattern() + ": routes could never declare that type");
            }
            otherPaths.put(type, split(paths, clientId));
        });
    }

    public Set<String> from(Map<String, Object> claims) {
        Set<String> authorities = new LinkedHashSet<>();
        for (String[] path : groupPaths) {
            valuesAt(claims, path).forEach(v -> authorities.add(GatewayIdentity.GROUP_PREFIX + v));
        }
        for (String[] path : rolePaths) {
            valuesAt(claims, path).forEach(v -> authorities.add(GatewayIdentity.ROLE_PREFIX + v));
        }
        otherPaths.forEach((type, paths) -> {
            for (String[] path : paths) {
                spaceDelimitedValuesAt(claims, path).forEach(v -> authorities.add(type + ":" + v));
            }
        });
        return authorities;
    }

    private static List<String[]> split(List<String> paths, String clientId) {
        return paths.stream()
                .map(p -> p.replace("{client-id}", clientId == null ? "" : clientId))
                .map(p -> p.split("\\."))
                .toList();
    }

    private static List<String> valuesAt(Map<String, Object> claims, String[] path) {
        Object current = claimAt(claims, path);
        if (current instanceof Collection<?> values) {
            return values.stream().filter(v -> v != null && !v.toString().isBlank()).map(Object::toString).toList();
        }
        if (current instanceof String value && !value.isBlank()) {
            return List.of(value);
        }
        return List.of();
    }

    // RFC 8693 section 4.2: scope is one space-separated string. Groups and
    // roles never take this path: "Domain Admins" is one AD group, not two.
    private static List<String> spaceDelimitedValuesAt(Map<String, Object> claims, String[] path) {
        if (claimAt(claims, path) instanceof String value && !value.isBlank()) {
            return List.of(WHITESPACE.split(value.strip()));
        }
        return valuesAt(claims, path);
    }

    private static Object claimAt(Map<String, Object> claims, String[] path) {
        Object current = claims;
        for (String segment : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }
}
