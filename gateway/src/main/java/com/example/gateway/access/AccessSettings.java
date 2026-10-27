package com.example.gateway.access;

import java.util.Set;

/**
 * What the route access filter needs from {@code gateway.security.*}, kept
 * free of Spring Security types.
 *
 * @param gatewayCookieNames cookies owned by the gateway (session, login state,
 *                           saved request); never forwarded upstream
 * @param bearerChallenge    whether a 401 carries {@code WWW-Authenticate: Bearer}
 *                           (true when the provider accepts bearer tokens)
 */
public record AccessSettings(
        AccessPolicy.Mode defaultMode,
        String identityHeaderPrefix,
        int identityHeaderMaxLength,
        Set<String> gatewayCookieNames,
        boolean bearerChallenge
) {
    public AccessSettings {
        gatewayCookieNames = Set.copyOf(gatewayCookieNames);
    }
}
