package com.example.gateway.security;

import com.example.gateway.access.AccessCapabilities;
import com.example.gateway.audit.AuditEvent;
import com.example.gateway.security.support.EchoUpstream;
import com.example.gateway.security.support.FakeEntra;
import com.example.gateway.security.support.FakeKeycloak;
import com.example.gateway.security.support.TestSupport;
import com.example.gateway.security.support.TestSupport.RecordingSink;
import com.example.shared.routes.RouteAccess;
import com.example.shared.routes.RouteObo;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code access.obo} on the Keycloak channel: the gateway exchanges the token A
 * for the Entra token Keycloak stored at the Microsoft login, runs Entra's
 * On-Behalf-Of with its own app, and the backend gets that token, never the token A.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Real servers live in static fields: processTestAot would start them and never exit.
@DisabledInAotMode
class KeycloakOboIntegrationTest {

    static final EchoUpstream upstream = new EchoUpstream();
    static final FakeKeycloak keycloak = new FakeKeycloak();
    static final FakeEntra entra = new FakeEntra();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SecurityTestRoutes.register(registry, upstream.baseUrl());
        String route = "gateway.routes.store.properties.routes[9].";
        registry.add(route + "id", () -> "obo-route");
        registry.add(route + "path", () -> "/obo/**");
        registry.add(route + "target-uri", upstream::baseUrl);
        registry.add(route + "strip-prefix", () -> "1");
        registry.add(route + "access.any-of[0]", () -> "group:tools-httpbin");
        registry.add(route + "access.identity-provider", () -> "microsoft");
        registry.add(route + "access.forward-token", () -> "true");
        registry.add(route + "access.obo.scopes[0]", () -> "api://backend-b/pedidos.read");
        registry.add("gateway.security.enabled", () -> "true");
        registry.add("gateway.security.provider", () -> "keycloak");
        registry.add("gateway.security.public-base-url", () -> "http://gateway.test");
        registry.add("gateway.security.oidc.issuer", () -> FakeKeycloak.PUBLIC_ISSUER);
        registry.add("gateway.security.oidc.internal-issuer", keycloak::internalIssuer);
        registry.add("gateway.security.oidc.client-id", () -> FakeKeycloak.CLIENT_ID);
        registry.add("gateway.security.oidc.client-secret", () -> FakeKeycloak.CLIENT_SECRET);
        registry.add("gateway.security.obo.client-id", () -> FakeEntra.GATEWAY_APP);
        registry.add("gateway.security.obo.client-secret", () -> FakeEntra.GATEWAY_APP_SECRET);
        registry.add("gateway.security.obo.token-uri", entra::tokenUri);
        registry.add("gateway.security.session.keys[0].id", () -> "k1");
        registry.add("gateway.security.session.keys[0].secret", () -> TestSupport.KEY_1);
        registry.add("gateway.security.session.cookie-secure", () -> "false");
        registry.add("gateway.audit.enabled", () -> "true");
    }

    @TestConfiguration
    static class Beans {
        @Bean
        @Primary
        RecordingSink recordingSink() {
            return new RecordingSink();
        }
    }

    @AfterAll
    static void stop() {
        upstream.close();
        keycloak.close();
        entra.close();
    }

    @LocalServerPort
    int port;

    @Autowired
    RecordingSink audit;

    @Autowired
    AccessCapabilities capabilities;

    WebTestClient client;
    FakeKeycloak.User alice;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        alice = FakeKeycloak.User.of("alice", List.of("tools-httpbin"), List.of()).viaIdentityProvider("microsoft");
        keycloak.forgetIdentityProviderTokens();
        keycloak.storeIdentityProviderToken(alice, "microsoft",
                entra.gatewayAppToken(alice.subject(), "alice", Duration.ofHours(1)), Instant.now().plus(Duration.ofHours(1)));
        keycloak.resetCounters();
        entra.reset();
        upstream.reset();
        audit.clear();
    }

    @Test
    void backendGetsTheEntraTokenForItsScopeAndNeverTheTokenA() throws Exception {
        String session = login(alice);

        client.get().uri("/obo/headers").cookie("GW_SESSION", session).exchange().expectStatus().isOk();

        JWTClaimsSet forwarded = forwardedClaims();
        assertThat(forwarded.getIssuer()).isEqualTo(FakeEntra.ISSUER);
        assertThat(forwarded.getAudience()).containsExactly("api://backend-b");
        assertThat(forwarded.getStringClaim("scp")).isEqualTo("pedidos.read");
        assertThat(forwarded.getStringClaim("oid")).isEqualTo(alice.subject());
        assertThat(forwarded.getStringClaim("azp")).isEqualTo(FakeEntra.GATEWAY_APP);
    }

    @Test
    void bothTokensComeFromTheCacheOnTheNextRequest() {
        String session = login(alice);

        client.get().uri("/obo/a").cookie("GW_SESSION", session).exchange().expectStatus().isOk();
        client.get().uri("/obo/b").cookie("GW_SESSION", session).exchange().expectStatus().isOk();

        assertThat(keycloak.exchangeCalls()).isEqualTo(1);
        assertThat(entra.oboCalls()).isEqualTo(1);
    }

    @Test
    void withoutAStoredMicrosoftTokenTheBrowserLogsInThroughMicrosoftAgain() {
        String session = login(alice);
        keycloak.forgetIdentityProviderTokens();

        client.get().uri("/obo/api").cookie("GW_SESSION", session).accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE,
                        challenge -> assertThat(challenge).contains("insufficient_user_authentication"));
        EntityExchangeResult<byte[]> hit = client.get().uri("/obo/headers").cookie("GW_SESSION", session)
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus().isFound()
                .expectBody().returnResult();

        assertThat(hit.getResponseHeaders().getLocation()).hasToString("/_gateway/oauth2/authorization/keycloak?idp=microsoft");
        assertThat(upstream.lastHeaders()).isEmpty();
        assertThat(audit.awaitPath("/obo/headers").reason()).isEqualTo("identity_provider_login_required");
    }

    @Test
    void entraRefusingIs502AndEntraDownIs503() {
        String session = login(alice);

        entra.setRefusing(true);
        client.get().uri("/obo/a").cookie("GW_SESSION", session).exchange().expectStatus().isEqualTo(502);
        assertThat(audit.awaitPath("/obo/a").reason()).isEqualTo("token_exchange_failed");
        entra.setRefusing(false);
        entra.setDown(true);
        client.get().uri("/obo/b").cookie("GW_SESSION", session).exchange().expectStatus().isEqualTo(503);

        assertThat(upstream.lastHeaders()).isEmpty();
    }

    @Test
    void bearerCallerWithoutAStoredTokenIsForbidden() {
        keycloak.forgetIdentityProviderTokens();

        client.get().uri("/obo/headers").header(HttpHeaders.AUTHORIZATION, "Bearer " + keycloak.accessToken(alice))
                .exchange()
                .expectStatus().isForbidden();

        AuditEvent event = audit.awaitPath("/obo/headers");
        assertThat(event.reason()).isEqualTo("identity_provider_login_required");
    }

    @Test
    void theGatewayWithAnEntraAppServesOboRoutes() {
        RouteAccess obo = new RouteAccess(null, List.of("group:tools-httpbin"), true, null, "microsoft", null,
                new RouteObo(List.of("api://backend-b/pedidos.read")));

        assertThat(capabilities.unsupported(obo)).isEmpty();
    }

    private JWTClaimsSet forwardedClaims() throws Exception {
        String authorization = upstream.lastHeaders().get(HttpHeaders.AUTHORIZATION).getFirst();
        assertThat(authorization).startsWith("Bearer ");
        return SignedJWT.parse(authorization.substring("Bearer ".length())).getJWTClaimsSet();
    }

    /** A completed login of {@code user}; returns the session cookie. */
    private String login(FakeKeycloak.User user) {
        EntityExchangeResult<byte[]> start = client.get().uri("/_gateway/oauth2/authorization/keycloak")
                .exchange()
                .expectStatus().isFound()
                .expectBody().returnResult();
        Map<String, String> params = TestSupport.query(start.getResponseHeaders().getLocation().toString());
        keycloak.prepareLogin(user, params.get("nonce"));
        EntityExchangeResult<byte[]> callback = client.get()
                .uri("/_gateway/login/oauth2/code/keycloak?code=abc&state={state}", params.get("state"))
                .cookie("GW_AUTHZ", TestSupport.cookie(start, "GW_AUTHZ"))
                .exchange()
                .expectStatus().isFound()
                .expectBody().returnResult();
        String session = TestSupport.cookie(callback, "GW_SESSION");
        assertThat(session).isNotBlank();
        upstream.reset();
        audit.clear();
        return session;
    }
}
