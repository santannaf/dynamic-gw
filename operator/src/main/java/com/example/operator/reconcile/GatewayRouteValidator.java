package com.example.operator.reconcile;

import com.example.operator.crd.GatewayRoute;
import com.example.operator.crd.GatewayRouteSpec;

import java.util.Optional;
import java.util.Set;

public class GatewayRouteValidator {

    private static final Set<String> ALLOWED_METHODS = Set.of(
            "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"
    );

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
        return Optional.empty();
    }
}
