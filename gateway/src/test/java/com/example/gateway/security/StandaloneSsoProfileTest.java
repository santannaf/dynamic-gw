package com.example.gateway.security;

import com.example.gateway.routes.store.PropertiesRouteConfigProvider;
import com.example.gateway.routes.store.RouteConfigProvider;
import com.example.gateway.routing.InMemoryDynamicRouteLocator;
import com.example.shared.routes.RouteAccess;
import com.example.shared.routes.RouteConfigEntry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The v1 profile with SSO, combined with the keycloak profile as in
 * k8s/sso/gateway-standalone-keycloak.yaml: routes with `access` from the
 * embedded YAML, security and audit on, and only the secrets (client secret,
 * session key) coming from outside.
 */
@SpringBootTest(properties = {
        "gateway.security.oidc.client-secret=test-secret",
        "gateway.security.session.keys[0].id=k1",
        "gateway.security.session.keys[0].secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@ActiveProfiles({"standalone-sso", "keycloak"})
class StandaloneSsoProfileTest {

    @Autowired
    ActiveSecurity security;

    @Autowired
    RouteConfigProvider provider;

    @Autowired
    InMemoryDynamicRouteLocator routeLocator;

    @Test
    void profilesTurnSecurityOnWithKeycloak() {
        assertThat(security.enabled()).isTrue();
        assertThat(security.provider(GatewaySecurityProperties.PROVIDER_KEYCLOAK)).isTrue();
        assertThat(security.properties().getDefaultAccess()).isEqualTo("authenticated");
    }

    @Test
    void embeddedRoutesCarryTheirAccessPolicies() {
        assertThat(provider).isInstanceOf(PropertiesRouteConfigProvider.class);
        Map<String, RouteConfigEntry> routes = provider.load().routes().stream()
                .collect(Collectors.toMap(RouteConfigEntry::id, Function.identity()));

        assertThat(routes.get("httpbin-route").access())
                .isEqualTo(new RouteAccess(null, List.of("group:tools-httpbin"), null));
        assertThat(routes.get("anything-route").access()).isEqualTo(new RouteAccess("public", null, null));
        assertThat(routes.get("me-route").access()).isNull();
        assertThat(routes.get("admin-route").access())
                .isEqualTo(new RouteAccess(null, List.of("role:gateway-admin", "group:tools-admins"), true));
        assertThat(routeLocator.size()).isEqualTo(4);
    }
}
