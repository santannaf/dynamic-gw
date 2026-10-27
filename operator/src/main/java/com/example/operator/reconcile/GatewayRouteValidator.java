package com.example.operator.reconcile;

import com.example.operator.crd.GatewayRoute;
import com.example.operator.crd.GatewayRouteAccess;
import com.example.operator.crd.GatewayRouteSpec;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public class GatewayRouteValidator {

    private static final Set<String> ALLOWED_METHODS = Set.of(
            "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"
    );
    private static final Set<String> ALLOWED_ACCESS_MODES = Set.of("public", "authenticated");
    private static final Pattern AUTHORITY = Pattern.compile("^(group|role):.+$");
    // Login, callback, logout and whoami live here; a route under this prefix
    // would be shadowed by the gateway's own endpoints.
    private static final String RESERVED_PATH_PREFIX = "/_gateway/";

    public Optional<String> validate(GatewayRoute resource) {
        if (resource == null) {
            return Optional.of("GatewayRoute is null");
        }
        if (resource.getMetadata() == null || resource.getMetadata().getName() == null
                || resource.getMetadata().getName().isBlank()) {
            return Optional.of("metadata.name is required");
        }
        GatewayRouteSpec spec = resource.getSpec();
        if (spec == null) {
            return Optional.of("spec is required");
        }
        if (spec.getPath() == null || spec.getPath().isBlank()) {
            return Optional.of("spec.path is required");
        }
        if (spec.getPath().startsWith(RESERVED_PATH_PREFIX)) {
            return Optional.of("spec.path must not start with the reserved prefix " + RESERVED_PATH_PREFIX);
        }
        if (spec.getTargetUri() == null || spec.getTargetUri().isBlank()) {
            return Optional.of("spec.targetUri is required");
        }
        if (spec.getStripPrefix() != null && spec.getStripPrefix() < 0) {
            return Optional.of("spec.stripPrefix must be >= 0");
        }
        if (spec.getMethods() != null) {
            for (String method : spec.getMethods()) {
                if (method == null || !ALLOWED_METHODS.contains(method)) {
                    return Optional.of("spec.methods contains invalid method '" + method + "'");
                }
            }
        }
        return validateAccess(spec.getAccess());
    }

    private static Optional<String> validateAccess(GatewayRouteAccess access) {
        if (access == null) {
            return Optional.empty();
        }
        if (access.getMode() != null && !ALLOWED_ACCESS_MODES.contains(access.getMode())) {
            return Optional.of("spec.access.mode must be one of " + ALLOWED_ACCESS_MODES
                    + " but was '" + access.getMode() + "'");
        }
        boolean hasAnyOf = access.getAnyOf() != null && !access.getAnyOf().isEmpty();
        if (hasAnyOf) {
            for (String authority : access.getAnyOf()) {
                if (authority == null || !AUTHORITY.matcher(authority).matches()) {
                    return Optional.of("spec.access.anyOf contains invalid authority '" + authority
                            + "' (expected group:<name> or role:<name>)");
                }
            }
        }
        if ("public".equals(access.getMode()) && hasAnyOf) {
            return Optional.of("spec.access.mode=public cannot be combined with a non-empty spec.access.anyOf");
        }
        return Optional.empty();
    }
}
