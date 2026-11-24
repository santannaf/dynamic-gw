package com.example.gateway.access;

import java.util.Set;

/**
 * What the route access filter needs from {@code gateway.security.*}, kept
 * free of Spring Security types.
 *
 * @param gatewayCookieNames cookies owned by the gateway (session, login state,
 *                           saved request); never forwarded upstream
 */
public record AccessSettings(
        AccessPolicy.Mode defaultMode,
        String identityHeaderPrefix,
        int identityHeaderMaxLength,
        Set<String> gatewayCookieNames
) {
    public AccessSettings {
        gatewayCookieNames = Set.copyOf(gatewayCookieNames);
    }
}
