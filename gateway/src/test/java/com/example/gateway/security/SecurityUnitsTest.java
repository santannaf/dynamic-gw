package com.example.gateway.security;

import com.example.gateway.security.keycloak.KeycloakTokenClient;
import com.example.gateway.security.ldap.LoginAttemptLimiter;
import com.example.gateway.security.session.ProviderUnavailableException;
import com.example.gateway.security.session.SessionRejectedException;
import com.example.gateway.security.support.FakeKeycloak;
import com.example.gateway.security.support.TestSupport.MutableClock;
import com.example.gateway.security.token.ClaimAuthorities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityUnitsTest {

    @Test
    void claimAuthoritiesReadGroupsRealmAndClientRoles() {
        ClaimAuthorities authorities = new ClaimAuthorities(
                List.of("groups"), List.of("realm_access.roles", "resource_access.{client-id}.roles"), Map.of(), "tools-gateway");

        assertThat(authorities.from(Map.of(
                "groups", List.of("tools-httpbin", "GG_KAFKA_READ"),
                "realm_access", Map.of("roles", List.of("gateway-admin")),
                "resource_access", Map.of("tools-gateway", Map.of("roles", List.of("viewer")),
                        "other-client", Map.of("roles", List.of("ignored"))))))
                .containsExactlyInAnyOrder("group:tools-httpbin", "group:GG_KAFKA_READ", "role:gateway-admin", "role:viewer");
        assertThat(authorities.from(Map.of("groups", "single"))).containsExactly("group:single");
        assertThat(authorities.from(Map.of())).isEmpty();
    }

    @Test
    void groupClaimLocationIsConfigurable() {
        ClaimAuthorities authorities = new ClaimAuthorities(List.of("ad_groups"), List.of(), Map.of(), "x");

        assertThat(authorities.from(Map.of("ad_groups", List.of("GG_TOOLS_HTTPBIN"), "groups", List.of("other"))))
                .containsExactly("group:GG_TOOLS_HTTPBIN");
    }

    @Test
    void otherClaimsBecomeAuthoritiesOfTheirType() {
        ClaimAuthorities authorities = new ClaimAuthorities(List.of(), List.of(),
                Map.of("scope", List.of("scope"), "permission", List.of("access.permissions")), "x");

        assertThat(authorities.from(Map.of(
                "scope", "openid  orders.read",
                "access", Map.of("permissions", List.of("conta ler", "conta-escrever")))))
                .containsExactlyInAnyOrder("scope:openid", "scope:orders.read",
                        "permission:conta ler", "permission:conta-escrever");
    }

    @Test
    void groupNameWithSpacesIsNeverSplitIntoOtherGroups() {
        ClaimAuthorities authorities = new ClaimAuthorities(List.of("groups"), List.of(), Map.of(), "x");

        assertThat(authorities.from(Map.of("groups", "Domain Admins"))).containsExactly("group:Domain Admins");
    }

    @ParameterizedTest
    @ValueSource(strings = {"group", "role"})
    void typesWithTheirOwnSettingCannotBeMappedByTheGenericClaims(String type) {
        assertThatThrownBy(() -> new ClaimAuthorities(List.of(), List.of(), Map.of(type, List.of("department")), "x"))
                .hasMessageContaining("gateway.security.authorities.claims")
                .hasMessageContaining(type + "-claims");
    }

    @Test
    void claimTypeThatNoRouteCouldDeclareFailsAtStartup() {
        assertThatThrownBy(() -> new ClaimAuthorities(List.of(), List.of(), Map.of("Scope", List.of("scope")), "x"))
                .hasMessageContaining("gateway.security.authorities.claims")
                .hasMessageContaining("'Scope'");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/pedidos?id=1", "/", "https://app.example.test/pedidos", "HTTPS://APP.EXAMPLE.TEST/x", "http://localhost:3000/x"})
    void pathsAndTrustedOriginsAreValidReturnTargets(String target) {
        ReturnTargets targets = new ReturnTargets(List.of("https://app.example.test", "http://localhost:3000"));

        assertThat(targets.parse(target)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://evil.test/", "//evil.test/x", "///evil.test", "////evil.test", "/\\evil.test",
            "https:///evil.test", "https://app.example.test@evil.test/",
            "https://user@app.example.test/", "https://app.example.test.evil.test/", "http://app.example.test/",
            "javascript:alert(1)", "pedidos", "", "https://app.example.test:8443/"})
    void anythingElseIsAnOpenRedirect(String target) {
        ReturnTargets targets = new ReturnTargets(List.of("https://app.example.test"));

        assertThat(targets.parse(target)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://*.example.test", "https://my_front.example.test", "app.example.test", "/pedidos"})
    void trustedOriginThatIsNotAnExactOriginFailsAtStartup(String origin) {
        assertThatThrownBy(() -> new ReturnTargets(List.of(origin)))
                .hasMessageContaining("gateway.security.front-origins")
                .hasMessageContaining(origin);
    }

    @Test
    void gatewayOwnPublicUrlIsTrustedAlongTheFronts() {
        GatewaySecurityProperties properties = new GatewaySecurityProperties();
        assertThat(properties.trustedOrigins()).isEmpty();

        properties.setFrontOrigins(List.of("https://app.example.test"));
        properties.setPublicBaseUrl("https://gateway.example.test");

        assertThat(properties.trustedOrigins()).containsExactly("https://app.example.test", "https://gateway.example.test");
    }

    @Test
    void loginLimiterCountsPerUsernameAndClient() {
        MutableClock clock = new MutableClock();
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(3, 3, Duration.ofMinutes(5), clock);

        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("Alice", "10.0.0." + i);
        }
        assertThat(limiter.isBlocked("alice", "10.0.0.99")).as("username counter is case-insensitive").isTrue();
        assertThat(limiter.isBlocked("bob", "10.0.0.1")).isFalse();

        clock.advance(Duration.ofMinutes(5));
        assertThat(limiter.isBlocked("alice", "10.0.0.99")).as("window slides").isFalse();

        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("user" + i, "10.9.9.9");
        }
        assertThat(limiter.isBlocked("someone-else", "10.9.9.9")).isTrue();
        limiter.reset("user0");
    }

    @Test
    void tokenClientDistinguishesEndedSessionsFromKeycloakOutages() {
        try (FakeKeycloak keycloak = new FakeKeycloak()) {
            KeycloakTokenClient client = new KeycloakTokenClient(WebClient.builder().build(),
                    keycloak.internalIssuer() + "/protocol/openid-connect/token",
                    keycloak.internalIssuer() + "/protocol/openid-connect/logout",
                    FakeKeycloak.CLIENT_ID, FakeKeycloak.CLIENT_SECRET);

            assertThatThrownBy(() -> client.refresh("unknown").block(Duration.ofSeconds(5)))
                    .isInstanceOf(SessionRejectedException.class);
            assertThatThrownBy(() -> client.refresh(null).block(Duration.ofSeconds(5)))
                    .isInstanceOf(SessionRejectedException.class);

            keycloak.setDown(true);
            assertThatThrownBy(() -> client.refresh("anything").block(Duration.ofSeconds(5)))
                    .isInstanceOf(ProviderUnavailableException.class);

            KeycloakTokenClient unreachable = new KeycloakTokenClient(WebClient.builder().build(),
                    "http://127.0.0.1:1/token", "http://127.0.0.1:1/logout", "c", "s");
            assertThatThrownBy(() -> unreachable.refresh("rt").block(Duration.ofSeconds(15)))
                    .isInstanceOf(ProviderUnavailableException.class);
            assertThat(unreachable.logout("rt").blockOptional(Duration.ofSeconds(15))).isEmpty();
        }
    }

    @Test
    void enabledSecurityRejectsUnknownProviderAndBadSettings() {
        GatewaySecurityProperties properties = enabledProperties();
        properties.setProvider("saml");
        assertThatThrownBy(() -> ActiveSecurity.from(properties, Clock.systemUTC()))
                .hasMessageContaining("gateway.security.provider");

        properties.setProvider("ldap");
        properties.setDefaultAccess("everyone");
        assertThatThrownBy(() -> ActiveSecurity.from(properties, Clock.systemUTC()))
                .hasMessageContaining("gateway.security.default-access");

        properties.setDefaultAccess("authenticated");
        properties.setPublicBaseUrl(" ");
        assertThatThrownBy(() -> ActiveSecurity.from(properties, Clock.systemUTC()))
                .hasMessageContaining("gateway.security.public-base-url");

        properties.setPublicBaseUrl("http://gateway.test");
        properties.getSession().setKeys(List.of());
        assertThatThrownBy(() -> ActiveSecurity.from(properties, Clock.systemUTC()))
                .hasMessageContaining("gateway.security.session.keys");
    }

    @Test
    void providersFailFastOnMissingConfiguration() {
        GatewaySecurityProperties keycloak = enabledProperties();
        assertThatThrownBy(() -> ActiveSecurity.from(keycloak, Clock.systemUTC()))
                .hasMessageContaining("gateway.security.oidc.issuer");

        GatewaySecurityProperties ldap = enabledProperties();
        ldap.setProvider("ldap");
        assertThatThrownBy(() -> ActiveSecurity.from(ldap, Clock.systemUTC()))
                .hasMessageContaining("gateway.security.ldap.url");

        GatewaySecurityProperties oauth = enabledProperties();
        oauth.setProvider("oauth");
        assertThatThrownBy(() -> ActiveSecurity.from(oauth, Clock.systemUTC()))
                .hasMessageContaining("gateway.security.oauth.issuer");
    }

    @Test
    void channelWithoutSessionNeedsNoCookieKeyNorPublicUrl() {
        GatewaySecurityProperties properties = new GatewaySecurityProperties();
        properties.setEnabled(true);
        properties.setProvider("oauth");
        properties.getOauth().setIssuer("http://issuer.test");
        properties.getOauth().setJwkSetUri("http://issuer.test/jwks");
        properties.getOauth().setAudience("api");

        ActiveSecurity security = ActiveSecurity.from(properties, Clock.systemUTC());

        assertThat(security.sessionManager()).isNull();
        assertThat(security.accessSettings().gatewayCookieNames()).isEmpty();
    }

    @Test
    void disabledSecurityNeedsNoConfiguration() {
        ActiveSecurity security = ActiveSecurity.from(new GatewaySecurityProperties(), Clock.systemUTC());

        assertThat(security.enabled()).isFalse();
        assertThat(security.sessionManager()).isNull();
    }

    private static GatewaySecurityProperties enabledProperties() {
        GatewaySecurityProperties properties = new GatewaySecurityProperties();
        properties.setEnabled(true);
        properties.setPublicBaseUrl("http://gateway.test");
        GatewaySecurityProperties.Session.Key key = new GatewaySecurityProperties.Session.Key();
        key.setId("k1");
        key.setSecret(com.example.gateway.security.support.TestSupport.KEY_1);
        properties.getSession().setKeys(List.of(key));
        return properties;
    }
}
