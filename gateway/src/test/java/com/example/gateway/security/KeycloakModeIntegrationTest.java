package com.example.gateway.security;

import com.example.gateway.audit.AuditEvent;
import com.example.gateway.security.support.EchoUpstream;
import com.example.gateway.security.support.FakeKeycloak;
import com.example.gateway.security.support.TestSupport;
import com.example.gateway.security.support.TestSupport.MutableClock;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider {@code keycloak} end to end against a fake Keycloak and an echo
 * upstream, with routes from properties (the v1 store also carries access).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Real servers live in static fields: processTestAot would start them and never exit.
@DisabledInAotMode
class KeycloakModeIntegrationTest {

    static final EchoUpstream upstream = new EchoUpstream();
    static final FakeKeycloak keycloak = new FakeKeycloak();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SecurityTestRoutes.register(registry, upstream.baseUrl());
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

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
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

    @Autowired
    MutableClock clock;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        upstream.reset();
        audit.clear();
        keycloak.setDown(false);
        keycloak.resetCounters();
        clock.reset();
    }

    @Test
    void publicRouteIsForwardedWithoutSessionAndSpoofedIdentityIsRemoved() {
        client.get().uri("/public/anything")
                .header("X-Auth-Request-User", "admin")
                .header("x-auth-request-groups", "tools-admins")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist(HttpHeaders.SET_COOKIE);

        assertThat(upstream.lastHeaders()).doesNotContainKeys("X-Auth-Request-User", "X-Auth-Request-Groups");
        AuditEvent event = audit.awaitPath("/public/anything");
        assertThat(event.action()).isEqualTo("route.access");
        assertThat(event.authMethod()).isEqualTo("none");
        assertThat(event.allowed()).isTrue();
    }

    @Test
    void apiCallWithoutCredentialsGets401WithBearerChallenge() {
        client.post().uri("/default/anything")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"dynamic-gateway\"");

        assertThat(upstream.lastHeaders()).isEmpty();
        AuditEvent event = audit.awaitPath("/default/anything");
        assertThat(event.reason()).isEqualTo("unauthenticated");
        assertThat(event.allowed()).isFalse();
    }

    @Test
    void curlStyleAcceptGets401NotRedirect() {
        client.get().uri("/default/anything")
                .header(HttpHeaders.ACCEPT, "*/*")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void browserNavigationIsSentToLogin() {
        client.get().uri("/group/anything")
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus().isFound()
                .expectHeader().location("/_gateway/oauth2/authorization/keycloak")
                .expectCookie().exists("REDIRECT_URI");
    }

    @Test
    void fullLoginFlowCreatesSessionUsableOnProtectedRoutes() {
        FakeKeycloak.User alice = FakeKeycloak.User.of("alice", List.of("tools-httpbin"), List.of());
        Session session = login(alice, "/group/anything");

        client.get().uri("/group/anything")
                .cookie("GW_SESSION", session.cookie)
                .cookie("theme", "dark")
                .exchange()
                .expectStatus().isOk();

        Map<String, List<String>> received = upstream.lastHeaders();
        assertThat(received.get("X-Auth-Request-User")).containsExactly("alice");
        assertThat(received.get("X-Auth-Request-Email")).containsExactly("alice@tools.local");
        assertThat(received.get("X-Auth-Request-Groups")).containsExactly("tools-httpbin");
        assertThat(received.get("Cookie")).containsExactly("theme=dark");
        assertThat(received).doesNotContainKey("Authorization");

        AuditEvent event = audit.awaitPath("/group/anything");
        assertThat(event.action()).isEqualTo("route.access");
        assertThat(event.userName()).isEqualTo("alice");
        assertThat(event.authMethod()).isEqualTo("session");
        assertThat(event.sessionId()).isEqualTo(alice.sid());
        assertThat(event.routeId()).isEqualTo("group-route");
        assertThat(event.matchedAuthority()).isEqualTo("group:tools-httpbin");
        assertThat(keycloak.refreshCalls()).as("cached session must not call Keycloak").isZero();

        client.get().uri("/_gateway/whoami")
                .cookie("GW_SESSION", session.cookie)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.user").isEqualTo("alice")
                .jsonPath("$.method").isEqualTo("session")
                .jsonPath("$.authorities[0]").isEqualTo("group:tools-httpbin");
    }

    @Test
    void userWithoutRequiredGroupGets403() {
        FakeKeycloak.User bob = FakeKeycloak.User.of("bob", List.of(), List.of());
        Session session = login(bob, "/group/anything");

        client.get().uri("/group/anything")
                .cookie("GW_SESSION", session.cookie)
                .exchange()
                .expectStatus().isForbidden();

        assertThat(upstream.lastHeaders()).isEmpty();
        AuditEvent event = audit.awaitPath("/group/anything");
        assertThat(event.reason()).isEqualTo("forbidden");
        assertThat(event.requiredAuthorities()).containsExactly("group:tools-httpbin");
        assertThat(event.userName()).isEqualTo("bob");
    }

    @Test
    void expiredCacheRefreshesOnceAndPicksUpGroupChanges() {
        FakeKeycloak.User alice = FakeKeycloak.User.of("alice", List.of("tools-httpbin"), List.of());
        Session session = login(alice, "/group/anything");

        clock.advance(Duration.ofMinutes(6));
        EntityExchangeResult<byte[]> refreshed = client.get().uri("/group/anything")
                .cookie("GW_SESSION", session.cookie)
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult();
        assertThat(keycloak.refreshCalls()).isEqualTo(1);
        String rotated = TestSupport.cookie(refreshed, "GW_SESSION");
        assertThat(rotated).as("refresh re-issues the cookie with the new refresh token").isNotBlank().isNotEqualTo(session.cookie);

        alice.withGroups(List.of());
        clock.advance(Duration.ofMinutes(6));
        client.get().uri("/group/anything")
                .cookie("GW_SESSION", rotated)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void endedKeycloakSessionEndsGatewaySession() {
        FakeKeycloak.User alice = FakeKeycloak.User.of("alice", List.of("tools-httpbin"), List.of());
        Session session = login(alice, "/group/anything");
        keycloak.endSession(alice.sid());
        clock.advance(Duration.ofMinutes(6));

        EntityExchangeResult<byte[]> result = client.get().uri("/group/anything")
                .cookie("GW_SESSION", session.cookie)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().returnResult();

        assertThat(TestSupport.expires(result, "GW_SESSION")).isTrue();
        assertThat(audit.awaitPath("/group/anything").reason()).isEqualTo("session_expired");
    }

    @Test
    void keycloakDownDuringRefreshGives503OnProtectedAndKeepsPublicRoutes() {
        FakeKeycloak.User alice = FakeKeycloak.User.of("alice", List.of("tools-httpbin"), List.of());
        Session session = login(alice, "/group/anything");
        keycloak.setDown(true);
        clock.advance(Duration.ofMinutes(6));

        client.get().uri("/group/anything")
                .cookie("GW_SESSION", session.cookie)
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus().isEqualTo(503);
        client.get().uri("/public/anything")
                .cookie("GW_SESSION", session.cookie)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void logoutEndsBothSessions() {
        FakeKeycloak.User alice = FakeKeycloak.User.of("alice", List.of("tools-httpbin"), List.of());
        Session session = login(alice, "/group/anything");

        EntityExchangeResult<byte[]> result = client.get().uri("/_gateway/logout")
                .cookie("GW_SESSION", session.cookie)
                .exchange()
                .expectStatus().isFound()
                .expectBody().returnResult();

        String location = result.getResponseHeaders().getLocation().toString();
        assertThat(location).startsWith(FakeKeycloak.PUBLIC_ISSUER + "/protocol/openid-connect/logout?");
        Map<String, String> params = TestSupport.query(location);
        assertThat(params).containsEntry("client_id", FakeKeycloak.CLIENT_ID)
                .containsEntry("post_logout_redirect_uri", "http://gateway.test/")
                .containsKey("id_token_hint");
        assertThat(TestSupport.expires(result, "GW_SESSION")).isTrue();
        assertThat(keycloak.logoutCalls()).isEqualTo(1);
        assertThat(audit.awaitPath("/_gateway/logout").action()).isEqualTo("auth.logout");
    }

    @Test
    void serviceAccountBearerIsAcceptedAndTokenIsNotForwardedByDefault() {
        String token = keycloak.accessToken(FakeKeycloak.User.serviceAccount("tools-cli", List.of()));

        client.get().uri("/default/anything")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk();

        assertThat(upstream.lastHeaders().get("X-Auth-Request-Client-Id")).containsExactly("tools-cli");
        assertThat(upstream.lastHeaders()).doesNotContainKey("Authorization");
        AuditEvent event = audit.awaitPath("/default/anything");
        assertThat(event.authMethod()).isEqualTo("bearer");
        assertThat(event.clientId()).isEqualTo("tools-cli");
    }

    @Test
    void forwardTokenRouteRelaysTheAccessToken() {
        FakeKeycloak.User alice = FakeKeycloak.User.of("alice", List.of("tools-httpbin"), List.of());
        String token = keycloak.accessToken(alice);

        client.get().uri("/token/anything")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk();

        assertThat(upstream.lastHeaders().get("Authorization")).containsExactly("Bearer " + token);
    }

    @Test
    void tokenForAnotherAudienceIsRejected() {
        FakeKeycloak.User alice = FakeKeycloak.User.of("alice", List.of("tools-httpbin"), List.of());
        String token = keycloak.accessToken(alice, "account", "Bearer", Duration.ofMinutes(5));

        client.get().uri("/group/anything")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer realm=\"dynamic-gateway\", error=\"invalid_token\"");

        assertThat(audit.awaitPath("/group/anything").reason()).isEqualTo("invalid_token");
    }

    @Test
    void idTokenCannotBeUsedAsBearer() {
        FakeKeycloak.User alice = FakeKeycloak.User.of("alice", List.of("tools-httpbin"), List.of());
        String idTokenLike = keycloak.accessToken(alice, FakeKeycloak.CLIENT_ID, "ID", Duration.ofMinutes(5));

        client.get().uri("/group/anything")
                .headers(h -> h.setBearerAuth(idTokenLike))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void expiredTokenIsRejected() {
        FakeKeycloak.User alice = FakeKeycloak.User.of("alice", List.of("tools-httpbin"), List.of());
        String expired = keycloak.accessToken(alice, FakeKeycloak.CLIENT_ID, "Bearer", Duration.ofSeconds(-60));

        client.get().uri("/group/anything")
                .headers(h -> h.setBearerAuth(expired))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void foreignBearerOnPublicRoutePassesUntouched() {
        client.get().uri("/public/anything")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-keycloak-token")
                .exchange()
                .expectStatus().isOk();

        assertThat(upstream.lastHeaders().get("Authorization")).containsExactly("Bearer not-a-keycloak-token");
    }

    @Test
    void internalRoutesNeedTheAdminRoleButReloadStaysOpen() {
        String plain = keycloak.accessToken(FakeKeycloak.User.serviceAccount("tools-cli", List.of()));
        String admin = keycloak.accessToken(FakeKeycloak.User.serviceAccount("tools-cli", List.of("gateway-admin")));

        client.get().uri("/internal/routes").exchange().expectStatus().isUnauthorized();
        client.get().uri("/internal/routes").headers(h -> h.setBearerAuth(plain)).exchange().expectStatus().isForbidden();
        client.get().uri("/internal/routes").headers(h -> h.setBearerAuth(admin)).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.routes[?(@.id == 'group-route')].access.anyOf[0]").isEqualTo("group:tools-httpbin");
        client.post().uri("/internal/routes/reload").exchange().expectStatus().isOk();
        assertThat(audit.awaitPath("/internal/routes/reload").action()).isEqualTo("internal.routes.reload");
    }

    @Test
    void springSecurityDoesNotHijackLoginOrLogoutPathsOfTools() {
        client.get().uri("/login").accept(MediaType.TEXT_HTML).exchange().expectStatus().isOk();
        assertThat(upstream.lastHeaders()).isNotEmpty();
        upstream.reset();
        client.post().uri("/logout").exchange().expectStatus().isOk();
        assertThat(upstream.lastHeaders()).isNotEmpty();
    }

    @Test
    void unmatchedPathIsAuditedAsNoRoute() {
        client.get().uri("/does-not-exist").exchange().expectStatus().isNotFound();

        AuditEvent event = audit.awaitPath("/does-not-exist");
        assertThat(event.action()).isEqualTo("request.unmatched");
        assertThat(event.reason()).isEqualTo("no_route");
    }


    /** Drives the browser flow: protected URL → login start → (fake) Keycloak → callback. */
    private Session login(FakeKeycloak.User user, String target) {
        EntityExchangeResult<byte[]> protectedHit = client.get().uri(target)
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus().isFound()
                .expectBody().returnResult();
        String savedRequest = TestSupport.cookie(protectedHit, "REDIRECT_URI");

        EntityExchangeResult<byte[]> start = client.get().uri("/_gateway/oauth2/authorization/keycloak")
                .cookie("REDIRECT_URI", savedRequest)
                .exchange()
                .expectStatus().isFound()
                .expectBody().returnResult();
        String authorize = start.getResponseHeaders().getLocation().toString();
        assertThat(authorize).startsWith(FakeKeycloak.PUBLIC_ISSUER + "/protocol/openid-connect/auth?");
        Map<String, String> params = TestSupport.query(authorize);
        assertThat(params).containsEntry("code_challenge_method", "S256")
                .containsEntry("redirect_uri", "http://gateway.test/_gateway/login/oauth2/code/keycloak");
        String authzCookie = TestSupport.cookie(start, "GW_AUTHZ");
        assertThat(authzCookie).isNotBlank();

        keycloak.prepareLogin(user, params.get("nonce"));
        EntityExchangeResult<byte[]> callback = client.get()
                .uri("/_gateway/login/oauth2/code/keycloak?code=abc&state={state}", params.get("state"))
                .cookie("GW_AUTHZ", authzCookie)
                .cookie("REDIRECT_URI", savedRequest)
                .exchange()
                .expectStatus().isFound()
                .expectBody().returnResult();
        assertThat(callback.getResponseHeaders().getLocation().getPath()).isEqualTo(target);
        String sessionCookie = TestSupport.cookie(callback, "GW_SESSION");
        assertThat(sessionCookie).as("callback sets the session cookie").isNotBlank();
        assertThat(new String(callback.getResponseBodyContent() == null ? new byte[0] : callback.getResponseBodyContent()))
                .doesNotContain("eyJ");

        AuditEvent loginEvent = audit.awaitLast(e -> "auth.login".equals(e.action()));
        assertThat(loginEvent.allowed()).isTrue();
        assertThat(loginEvent.userName()).isEqualTo(user.username());
        upstream.reset();
        audit.clear();
        return new Session(sessionCookie);
    }

    record Session(String cookie) {
    }
}
