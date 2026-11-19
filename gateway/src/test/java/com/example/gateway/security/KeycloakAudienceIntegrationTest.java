package com.example.gateway.security;

import com.example.gateway.audit.AuditEvent;
import com.example.gateway.security.support.EchoUpstream;
import com.example.gateway.security.support.FakeKeycloak;
import com.example.gateway.security.support.TestSupport;
import com.example.gateway.security.support.TestSupport.RecordingSink;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code access.audience} on the Keycloak channel: the backend receives a
 * token exchanged for its client instead of the caller's token A.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Real servers live in static fields: processTestAot would start them and never exit.
@DisabledInAotMode
class KeycloakAudienceIntegrationTest {

    static final EchoUpstream upstream = new EchoUpstream();
    static final FakeKeycloak keycloak = new FakeKeycloak();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SecurityTestRoutes.register(registry, upstream.baseUrl());
        audienceRoute(registry, 9, "aud-route", "/aud/**", "httpbin-api");
        audienceRoute(registry, 10, "aud-refused-route", "/aud-refused/**", "not-on-the-client");
        audienceRoute(registry, 11, "aud-limited-route", "/aud-limited/**", "httpbin-api");
        registry.add("gateway.routes.store.properties.routes[11].rate-limit.requests", () -> "1");
        registry.add("gateway.routes.store.properties.routes[11].rate-limit.period", () -> "1m");
        registry.add("gateway.routes.store.properties.routes[11].rate-limit.key", () -> "user");
        registry.add("gateway.security.enabled", () -> "true");
        registry.add("gateway.security.provider", () -> "keycloak");
        registry.add("gateway.security.public-base-url", () -> "http://gateway.test");
        registry.add("gateway.security.oidc.issuer", () -> FakeKeycloak.PUBLIC_ISSUER);
        registry.add("gateway.security.oidc.internal-issuer", keycloak::internalIssuer);
        registry.add("gateway.security.oidc.client-id", () -> FakeKeycloak.CLIENT_ID);
        registry.add("gateway.security.oidc.client-secret", () -> FakeKeycloak.CLIENT_SECRET);
        registry.add("gateway.security.session.keys[0].id", () -> "k1");
        registry.add("gateway.security.session.keys[0].secret", () -> TestSupport.KEY_1);
        registry.add("gateway.security.session.cookie-secure", () -> "false");
        registry.add("gateway.audit.enabled", () -> "true");
    }

    private static void audienceRoute(DynamicPropertyRegistry registry, int index, String id, String path,
                                      String audience) {
        String route = "gateway.routes.store.properties.routes[" + index + "].";
        registry.add(route + "id", () -> id);
        registry.add(route + "path", () -> path);
        registry.add(route + "target-uri", upstream::baseUrl);
        registry.add(route + "strip-prefix", () -> "1");
        registry.add(route + "access.any-of[0]", () -> "group:tools-httpbin");
        registry.add(route + "access.forward-token", () -> "true");
        registry.add(route + "access.audience", () -> audience);
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
    }

    @LocalServerPort
    int port;

    @Autowired
    RecordingSink audit;

    WebTestClient client;
    // A new person per test: the exchanged token is cached per session, and the fake reuses a user's sid.
    FakeKeycloak.User alice;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        alice = FakeKeycloak.User.of("alice", List.of("tools-httpbin"), List.of());
        keycloak.allowAudience("httpbin-api");
        keycloak.setDown(false);
        keycloak.resetCounters();
        upstream.reset();
        audit.clear();
    }

    @Test
    void backendGetsATokenForItsAudienceStillIssuedToTheGateway() throws Exception {
        String session = login(alice);

        client.get().uri("/aud/headers").cookie("GW_SESSION", session).exchange().expectStatus().isOk();

        JWTClaimsSet forwarded = forwardedClaims();
        assertThat(forwarded.getAudience()).containsExactly("httpbin-api");
        assertThat(forwarded.getStringClaim("azp")).isEqualTo(FakeKeycloak.CLIENT_ID);
        assertThat(forwarded.getStringClaim("preferred_username")).isEqualTo("alice");
        assertThat(upstream.lastHeaders().get("X-Auth-Request-User")).containsExactly("alice");
    }

    @Test
    void theSecondRequestUsesTheCachedToken() {
        String session = login(alice);

        client.get().uri("/aud/a").cookie("GW_SESSION", session).exchange().expectStatus().isOk();
        client.get().uri("/aud/b").cookie("GW_SESSION", session).exchange().expectStatus().isOk();

        assertThat(keycloak.exchangeCalls()).isEqualTo(1);
    }

    @Test
    void bearerCallerAlsoGetsTheExchangedTokenSent() throws Exception {
        String tokenA = keycloak.accessToken(alice);

        client.get().uri("/aud/headers").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                .exchange().expectStatus().isOk();

        assertThat(upstream.lastHeaders().get(HttpHeaders.AUTHORIZATION)).doesNotContain("Bearer " + tokenA);
        assertThat(forwardedClaims().getAudience()).containsExactly("httpbin-api");
    }

    @Test
    void keycloakDownIs503AndNothingReachesTheBackend() {
        String session = login(alice);
        keycloak.setDown(true);

        client.get().uri("/aud/headers").cookie("GW_SESSION", session).exchange()
                .expectStatus().isEqualTo(503);

        assertThat(upstream.lastHeaders()).isEmpty();
        AuditEvent event = audit.awaitPath("/aud/headers");
        assertThat(event.reason()).isEqualTo("token_exchange_failed");
        assertThat(event.allowed()).isFalse();
    }

    @Test
    void audienceKeycloakRefusesIs502() {
        String session = login(alice);

        client.get().uri("/aud-refused/headers").cookie("GW_SESSION", session).exchange()
                .expectStatus().isEqualTo(502);

        assertThat(upstream.lastHeaders()).isEmpty();
        assertThat(audit.awaitPath("/aud-refused/headers").reason()).isEqualTo("token_exchange_failed");
    }

    @Test
    void aRateLimitedRequestCostsNoExchange() {
        String session = login(alice);

        client.get().uri("/aud-limited/a").cookie("GW_SESSION", session).exchange().expectStatus().isOk();
        client.get().uri("/aud-limited/b").cookie("GW_SESSION", session).exchange().expectStatus().isEqualTo(429);

        assertThat(keycloak.exchangeCalls()).isEqualTo(1);
    }

    @Test
    void routesWithoutAudienceKeepSendingTokenA() {
        String session = login(alice);

        client.get().uri("/token/headers").cookie("GW_SESSION", session).exchange().expectStatus().isOk();

        assertThat(keycloak.exchangeCalls()).isZero();
        assertThat(upstream.lastHeaders().get(HttpHeaders.AUTHORIZATION).getFirst()).startsWith("Bearer ey");
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
