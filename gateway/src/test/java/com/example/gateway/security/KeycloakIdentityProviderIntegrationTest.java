package com.example.gateway.security;

import com.example.gateway.audit.AuditEvent;
import com.example.gateway.security.support.EchoUpstream;
import com.example.gateway.security.support.FakeKeycloak;
import com.example.gateway.security.support.TestSupport;
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
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code access.identityProvider} on the Keycloak channel: the route only takes
 * a session that came through that Keycloak identity provider.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Real servers live in static fields: processTestAot would start them and never exit.
@DisabledInAotMode
class KeycloakIdentityProviderIntegrationTest {

    static final EchoUpstream upstream = new EchoUpstream();
    static final FakeKeycloak keycloak = new FakeKeycloak();
    static final FakeKeycloak.User ALICE = FakeKeycloak.User.of("alice", List.of("tools-httpbin"), List.of());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SecurityTestRoutes.register(registry, upstream.baseUrl());
        String route = "gateway.routes.store.properties.routes[9].";
        registry.add(route + "id", () -> "idp-route");
        registry.add(route + "path", () -> "/idp/**");
        registry.add(route + "target-uri", upstream::baseUrl);
        registry.add(route + "strip-prefix", () -> "1");
        registry.add(route + "access.any-of[0]", () -> "group:tools-httpbin");
        registry.add(route + "access.identity-provider", () -> "microsoft");
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

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        upstream.reset();
        audit.clear();
    }

    @Test
    void anonymousNavigationGoesStraightToTheIdentityProvider() {
        EntityExchangeResult<byte[]> hit = navigate("/idp/anything", null).expectStatus().isFound()
                .expectBody().returnResult();

        assertThat(hit.getResponseHeaders().getLocation()).hasToString("/_gateway/oauth2/authorization/keycloak?idp=microsoft");
        assertThat(TestSupport.cookie(hit, "GW_IDP_LOGIN")).isEqualTo("microsoft");
        Map<String, String> authorize = authorizeParameters("/_gateway/oauth2/authorization/keycloak?idp=microsoft");
        assertThat(authorize).containsEntry("kc_idp_hint", "microsoft")
                .containsEntry("prompt", "login")
                .containsEntry("code_challenge_method", "S256");
    }

    @Test
    void loginWithoutTheHintStaysAsItWas() {
        assertThat(authorizeParameters("/_gateway/oauth2/authorization/keycloak"))
                .doesNotContainKeys("kc_idp_hint", "prompt");
        assertThat(authorizeParameters("/_gateway/oauth2/authorization/keycloak?idp=not%20an%20alias"))
                .doesNotContainKeys("kc_idp_hint", "prompt");
    }

    @Test
    void sessionFromTheIdentityProviderReachesTheRoute() {
        String session = login(ALICE.viaIdentityProvider("microsoft"));

        client.get().uri("/idp/headers").cookie("GW_SESSION", session).cookie("GW_IDP_LOGIN", "microsoft")
                .exchange().expectStatus().isOk();

        assertThat(upstream.lastHeaders().get("X-Auth-Request-User")).containsExactly("alice");
        assertThat(upstream.lastHeaders()).doesNotContainKey(HttpHeaders.COOKIE);
        client.get().uri("/_gateway/whoami").cookie("GW_SESSION", session).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.identityProvider").isEqualTo("microsoft");
    }

    @Test
    void passwordSessionIsSentToLogInThroughTheProvider() {
        String session = login(ALICE);

        EntityExchangeResult<byte[]> hit = navigate("/idp/anything", session).expectStatus().isFound()
                .expectBody().returnResult();

        assertThat(hit.getResponseHeaders().getLocation()).hasToString("/_gateway/oauth2/authorization/keycloak?idp=microsoft");
        assertThat(upstream.lastHeaders()).isEmpty();
        AuditEvent event = audit.awaitPath("/idp/anything");
        assertThat(event.reason()).isEqualTo("wrong_identity_provider");
        assertThat(event.allowed()).isFalse();
    }

    @Test
    void passwordSessionOnAnApiCallGetsTheStepUpChallenge() {
        String session = login(ALICE);

        client.get().uri("/idp/anything").cookie("GW_SESSION", session).accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE, challenge -> assertThat(challenge)
                        .contains("error=\"insufficient_user_authentication\"")
                        .contains("log in through microsoft"));
    }

    @Test
    void sessionStillFromAnotherLoginAfterTheProviderLoginIsForbiddenNotLooped() {
        String session = login(ALICE);

        client.get().uri("/idp/anything").accept(MediaType.TEXT_HTML)
                .cookie("GW_SESSION", session).cookie("GW_IDP_LOGIN", "microsoft")
                .exchange()
                .expectStatus().isForbidden();

        assertThat(audit.awaitPath("/idp/anything").reason()).isEqualTo("wrong_identity_provider");
    }

    @Test
    void bearerFromAnotherLoginIsForbiddenAndFromTheProviderPasses() {
        client.get().uri("/idp/anything").header(HttpHeaders.AUTHORIZATION, "Bearer " + keycloak.accessToken(ALICE))
                .exchange()
                .expectStatus().isForbidden();
        client.get().uri("/idp/anything")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + keycloak.accessToken(ALICE.viaIdentityProvider("microsoft")))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void frontCanStartTheLoginThroughTheProvider() {
        client.get().uri("/_gateway/login/start?returnTo=/idp/anything&idp=microsoft").exchange()
                .expectStatus().isFound()
                .expectHeader().location("/_gateway/oauth2/authorization/keycloak?idp=microsoft");
        client.get().uri("/_gateway/login/start?returnTo=/idp/anything&idp={idp}", "micro soft").exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void otherRoutesTakeAnySession() {
        String session = login(ALICE);

        client.get().uri("/group/anything").cookie("GW_SESSION", session).exchange().expectStatus().isOk();
    }

    private WebTestClient.ResponseSpec navigate(String uri, String session) {
        WebTestClient.RequestHeadersSpec<?> request = client.get().uri(uri).accept(MediaType.TEXT_HTML);
        if (session != null) {
            request = request.cookie("GW_SESSION", session);
        }
        return request.exchange();
    }

    private Map<String, String> authorizeParameters(String start) {
        EntityExchangeResult<byte[]> redirect = client.get().uri(start).exchange()
                .expectStatus().isFound()
                .expectBody().returnResult();
        String authorize = redirect.getResponseHeaders().getLocation().toString();
        assertThat(authorize).startsWith(FakeKeycloak.PUBLIC_ISSUER + "/protocol/openid-connect/auth?");
        return TestSupport.query(authorize);
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
