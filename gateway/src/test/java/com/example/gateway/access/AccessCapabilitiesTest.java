package com.example.gateway.access;

import com.example.gateway.access.AccessCapabilities.Feature;
import com.example.shared.routes.RouteAccess;
import com.example.shared.routes.RouteObo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AccessCapabilitiesTest {

    private static final RouteObo PEDIDOS = new RouteObo(List.of("api://backend-b/pedidos.read"));
    private static final AccessCapabilities KEYCLOAK_WITH_EVERYTHING = new AccessCapabilities(true, "keycloak",
            Map.of("keycloak", Set.of(Feature.IDENTITY_PROVIDER, Feature.AUDIENCE, Feature.OBO), "ldap", Set.of()));

    @Test
    void routesWithoutTheNewFieldsAreAlwaysServed() {
        RouteAccess plain = new RouteAccess("authenticated", List.of("group:a"), true, null, null, null, null);

        assertThat(AccessCapabilities.disabled().unsupported(plain)).isEmpty();
        assertThat(AccessCapabilities.disabled().unsupported(null)).isEmpty();
        assertThat(KEYCLOAK_WITH_EVERYTHING.unsupported(plain)).isEmpty();
    }

    @Test
    void securityOffServesNoRouteThatAsksForAChannelFeature() {
        assertThat(AccessCapabilities.disabled().unsupported(access(null, "microsoft", null, null)))
                .hasValueSatisfying(problem -> assertThat(problem).contains("gateway.security.enabled=true"));
    }

    @Test
    void channelMustBeActive() {
        AccessCapabilities keycloakOnly = new AccessCapabilities(true, "keycloak", Map.of("keycloak", Set.of()));

        assertThat(keycloakOnly.unsupported(access("ldap", null, null, null)))
                .hasValueSatisfying(problem -> assertThat(problem).contains("access.channel=ldap is not active"));
        assertThat(keycloakOnly.unsupported(access("keycloak", null, null, null))).isEmpty();
    }

    @Test
    void eachFeatureNeedsAChannelThatEnforcesIt() {
        AccessCapabilities none = new AccessCapabilities(true, "keycloak", Map.of("keycloak", Set.of()));

        assertThat(none.unsupported(access(null, "microsoft", null, null)))
                .hasValueSatisfying(problem -> assertThat(problem).contains("cannot check access.identityProvider"));
        assertThat(none.unsupported(access(null, null, "abc-api", null)))
                .hasValueSatisfying(problem -> assertThat(problem).contains("cannot exchange a token for access.audience"));
        assertThat(none.unsupported(access(null, "microsoft", null, PEDIDOS)))
                .hasValueSatisfying(problem -> assertThat(problem).contains("cannot check access.identityProvider"));
        assertThat(new AccessCapabilities(true, "keycloak", Map.of("keycloak", Set.of(Feature.IDENTITY_PROVIDER)))
                .unsupported(access(null, "microsoft", null, PEDIDOS)))
                .hasValueSatisfying(problem -> assertThat(problem).contains("cannot get an Entra token for access.obo"));
    }

    @Test
    void theRouteChannelDecidesWhichFeaturesApply() {
        assertThat(KEYCLOAK_WITH_EVERYTHING.unsupported(access(null, "microsoft", null, PEDIDOS))).isEmpty();
        assertThat(KEYCLOAK_WITH_EVERYTHING.unsupported(access("keycloak", null, "abc-api", null))).isEmpty();
        assertThat(KEYCLOAK_WITH_EVERYTHING.unsupported(access("ldap", "microsoft", null, null)))
                .hasValueSatisfying(problem -> assertThat(problem).contains("the ldap channel"));
    }

    private static RouteAccess access(String channel, String identityProvider, String audience, RouteObo obo) {
        return new RouteAccess(null, List.of("group:a"), true, channel, identityProvider, audience, obo);
    }
}
