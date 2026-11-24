package com.example.operator.reconcile;

import com.example.operator.crd.GatewayRoute;
import com.example.operator.crd.GatewayRouteSpec;
import com.example.shared.routes.RoutePolicyRules;

import java.util.Optional;
import java.util.Set;

public class GatewayRouteValidator {

    private static final Set<String> ALLOWED_METHODS = Set.of(
            "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"
    );
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
        // Access, target (targetUri or aggregate) and policies: the same rules
        // the gateway applies to routes declared in properties.
        return RoutePolicyRules.validate(RouteEntries.toEntry(resource),
                        RoutePolicyRules.DEFAULT_IDENTITY_HEADER_PREFIX)
                .map(problem -> "spec." + problem);
    }
}
