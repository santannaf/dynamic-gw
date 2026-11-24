package com.example.gateway.access;

import com.example.shared.routes.RouteAccess;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What the identity channels of this gateway can enforce. The reload refuses a
 * route whose access asks for more: served anyway, it would run without the
 * check it declares.
 */
public final class AccessCapabilities {

    public enum Feature { IDENTITY_PROVIDER, AUDIENCE, OBO }

    private final boolean enabled;
    private final String primaryChannel;
    private final Map<String, Set<Feature>> channels;

    public AccessCapabilities(boolean enabled, String primaryChannel, Map<String, Set<Feature>> channels) {
        this.enabled = enabled;
        this.primaryChannel = primaryChannel;
        this.channels = Map.copyOf(channels);
    }

    public static AccessCapabilities disabled() {
        return new AccessCapabilities(false, null, Map.of());
    }

    /** Why this gateway cannot serve the route's access, or empty when it can. */
    public Optional<String> unsupported(RouteAccess access) {
        if (access == null || (access.channel() == null && access.identityProvider() == null
                && access.audience() == null && access.obo() == null)) {
            return Optional.empty();
        }
        if (!enabled) {
            return Optional.of("access.channel, identityProvider, audience and obo need gateway.security.enabled=true");
        }
        String channel = access.channel() != null ? access.channel() : primaryChannel;
        Set<Feature> features = channels.get(channel);
        if (features == null) {
            return Optional.of("access.channel=" + channel + " is not active in this gateway"
                    + " (gateway.security.provider or additional-providers)");
        }
        if (access.identityProvider() != null && !features.contains(Feature.IDENTITY_PROVIDER)) {
            return Optional.of("the " + channel + " channel of this gateway cannot check access.identityProvider");
        }
        if (access.audience() != null && !features.contains(Feature.AUDIENCE)) {
            return Optional.of("the " + channel + " channel of this gateway cannot exchange a token for access.audience");
        }
        if (access.obo() != null && !features.contains(Feature.OBO)) {
            return Optional.of("the " + channel + " channel of this gateway cannot get an Entra token for access.obo"
                    + " (gateway.security.obo)");
        }
        return Optional.empty();
    }
}
