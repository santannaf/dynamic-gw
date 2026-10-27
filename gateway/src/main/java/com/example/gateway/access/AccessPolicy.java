package com.example.gateway.access;

import com.example.shared.routes.RouteAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Effective access policy of a route: the declared {@link RouteAccess}
 * merged with the gateway default. Unknown modes fail closed.
 */
public record AccessPolicy(Mode mode, List<String> anyOf, boolean forwardToken) {

    private static final Logger log = LoggerFactory.getLogger(AccessPolicy.class);

    public AccessPolicy {
        anyOf = anyOf == null ? List.of() : List.copyOf(anyOf);
    }

    public static AccessPolicy of(String routeId, RouteAccess declared, Mode defaultMode) {
        if (declared == null) {
            return new AccessPolicy(defaultMode, List.of(), false);
        }
        List<String> anyOf = declared.anyOf() == null ? List.of() : declared.anyOf();
        Mode mode;
        if (declared.mode() == null) {
            mode = anyOf.isEmpty() ? defaultMode : Mode.AUTHENTICATED;
        } else {
            mode = Mode.parse(declared.mode());
            if (mode == null) {
                log.warn("Route {} declares unknown access.mode '{}'; treating it as authenticated",
                        routeId, declared.mode());
                mode = Mode.AUTHENTICATED;
            }
        }
        if (mode == Mode.PUBLIC && !anyOf.isEmpty()) {
            // The CRD and the operator reject this combination; a snapshot that
            // still carries it gets the restrictive reading.
            mode = Mode.AUTHENTICATED;
        }
        return new AccessPolicy(mode, anyOf, Boolean.TRUE.equals(declared.forwardToken()));
    }

    public boolean requiresIdentity() {
        return mode == Mode.AUTHENTICATED;
    }

    public enum Mode {
        PUBLIC("public"),
        AUTHENTICATED("authenticated");

        private final String value;

        Mode(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Mode parse(String value) {
            for (Mode mode : values()) {
                if (mode.value.equals(value)) {
                    return mode;
                }
            }
            return null;
        }
    }
}
