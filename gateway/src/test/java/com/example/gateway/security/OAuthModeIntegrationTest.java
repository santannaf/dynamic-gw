package com.example.gateway.security;

import com.example.gateway.audit.AuditEvent;
import com.example.gateway.security.support.EchoUpstream;
import com.example.gateway.security.support.FakeKeycloak;
import com.example.gateway.security.support.TestSupport.RecordingSink;
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
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider {@code oauth} end to end: a channel with no session, proving that
 * routes, enforcement, identity headers and audit do not depend on one. The
 * fake Keycloak only plays the authorization server (JWKS and signed tokens).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Real servers live in static fields: processTestAot would start them and never exit.
@DisabledInAotMode
class OAuthModeIntegrationTest {

    static final EchoUpstream upstream = new EchoUpstream();
    static final FakeKeycloak issuer = new FakeKeycloak();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SecurityTestRoutes.register(registry, upstream.baseUrl());
        registry.add("gateway.security.enabled", () -> "true");
        registry.add("gateway.security.provider", () -> "oauth");
        registry.add("gateway.security.oauth.issuer", () -> FakeKeycloak.PUBLIC_ISSUER);
        registry.add("gateway.security.oauth.jwk-set-uri", () -> issuer.internalIssuer() + "/protocol/openid-connect/certs");
        registry.add("gateway.security.oauth.audience", () -> FakeKeycloak.CLIENT_ID);
        registry.add("gateway.security.oauth.required-token-type", () -> "Bearer");
        registry.add("gateway.security.authorities.claims.scope[0]", () -> "scope");
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
        issuer.close();
    }

    @LocalServerPort
    int port;

    @Autowired
    RecordingSink audit;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        upstream.reset();
        audit.clear();
    }

    @Test
    void tokenWithTheScopeReachesTheBackendAndIsAudited() {
        String token = tokenWithScope("openid orders.read");

        client.get().uri("/scope/anything")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist(HttpHeaders.SET_COOKIE);

        assertThat(upstream.lastHeaders().get("X-Auth-Request-Authorities"))
                .containsExactly("scope:openid,scope:orders.read");
        assertThat(upstream.lastHeaders().get("X-Auth-Request-Client-Id")).containsExactly("orders-app");
        assertThat(upstream.lastHeaders()).doesNotContainKey("Authorization");
        AuditEvent event = audit.awaitPath("/scope/anything");
        assertThat(event.authMethod()).isEqualTo("bearer");
        assertThat(event.matchedAuthority()).isEqualTo("scope:orders.read");
    }

    @Test
    void tokenWithoutTheScopeGets403() {
        client.get().uri("/scope/anything")
                .headers(h -> h.setBearerAuth(tokenWithScope("openid")))
                .exchange()
                .expectStatus().isForbidden();

        assertThat(upstream.lastHeaders()).isEmpty();
        assertThat(audit.awaitPath("/scope/anything").reason()).isEqualTo("forbidden");
    }

    @Test
    void browserWithoutTokenGets401BecauseThisChannelHasNoLogin() {
        client.get().uri("/scope/anything")
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"dynamic-gateway\"")
                .expectHeader().doesNotExist(HttpHeaders.SET_COOKIE);

        assertThat(audit.awaitPath("/scope/anything").reason()).isEqualTo("unauthenticated");
    }

    @Test
    void invalidTokenGets401() {
        String expired = issuer.accessToken(FakeKeycloak.User.serviceAccount("orders-app", List.of()),
                FakeKeycloak.CLIENT_ID, "Bearer", Duration.ofSeconds(-60));

        client.get().uri("/scope/anything")
                .headers(h -> h.setBearerAuth(expired))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer realm=\"dynamic-gateway\", error=\"invalid_token\"");

        assertThat(audit.awaitPath("/scope/anything").reason()).isEqualTo("invalid_token");
    }

    @Test
    void tokenForAnotherAudienceIsRejected() {
        String token = issuer.accessToken(FakeKeycloak.User.serviceAccount("orders-app", List.of()),
                "another-api", "Bearer", Duration.ofMinutes(5));

        client.get().uri("/scope/anything")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void publicRouteNeedsNothingAndForeignTokenPassesUntouched() {
        client.get().uri("/public/anything")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-ours")
                .exchange()
                .expectStatus().isOk();

        assertThat(upstream.lastHeaders().get("Authorization")).containsExactly("Bearer not-ours");
    }

    @Test
    void forwardTokenStillWorks() {
        String token = issuer.accessToken(FakeKeycloak.User.of("alice", List.of("tools-httpbin"), List.of()));

        client.get().uri("/token/anything")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk();

        assertThat(upstream.lastHeaders().get("Authorization")).containsExactly("Bearer " + token);
    }

    @Test
    void whoamiWorksAndSessionEndpointsDoNotExist() {
        client.get().uri("/_gateway/whoami")
                .headers(h -> h.setBearerAuth(tokenWithScope("orders.read")))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.method").isEqualTo("bearer")
                .jsonPath("$.authorities[0]").isEqualTo("scope:orders.read");

        client.get().uri("/_gateway/logout").exchange().expectStatus().isNotFound();
        client.get().uri("/_gateway/login/start").exchange().expectStatus().isNotFound();
        client.get().uri("/_gateway/login").accept(MediaType.TEXT_HTML).exchange().expectStatus().isNotFound();
    }

    @Test
    void internalRoutesNeedTheAdminRole() {
        String plain = issuer.accessToken(FakeKeycloak.User.serviceAccount("orders-app", List.of()));
        String admin = issuer.accessToken(FakeKeycloak.User.serviceAccount("orders-app", List.of("gateway-admin")));

        client.get().uri("/internal/routes").exchange().expectStatus().isUnauthorized();
        client.get().uri("/internal/routes").headers(h -> h.setBearerAuth(plain)).exchange().expectStatus().isForbidden();
        client.get().uri("/internal/routes").headers(h -> h.setBearerAuth(admin)).exchange().expectStatus().isOk();
    }

    private static String tokenWithScope(String scope) {
        return issuer.accessTokenWithScope(FakeKeycloak.User.serviceAccount("orders-app", List.of()), scope);
    }
}
