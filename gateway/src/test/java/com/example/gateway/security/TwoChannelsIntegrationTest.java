package com.example.gateway.security;

import com.example.gateway.security.support.EchoUpstream;
import com.example.gateway.security.support.FakeKeycloak;
import com.example.gateway.security.support.TestDirectory;
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
import org.springframework.web.reactive.function.BodyInserters;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ldap channel next to keycloak (additional-providers): routes with
 * {@code access.channel: ldap} take the session of the gateway's login form,
 * the others the Keycloak one, each in its own cookie.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Real servers live in static fields: processTestAot would start them and never exit.
@DisabledInAotMode
class TwoChannelsIntegrationTest {

    static final EchoUpstream upstream = new EchoUpstream();
    static final FakeKeycloak keycloak = new FakeKeycloak();
    static final TestDirectory directory = new TestDirectory();
    private static final Pattern CSRF_FIELD = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SecurityTestRoutes.register(registry, upstream.baseUrl());
        String route = "gateway.routes.store.properties.routes[9].";
        registry.add(route + "id", () -> "ldap-route");
        registry.add(route + "path", () -> "/ldap/**");
        registry.add(route + "target-uri", upstream::baseUrl);
        registry.add(route + "strip-prefix", () -> "1");
        registry.add(route + "access.channel", () -> "ldap");
        registry.add(route + "access.any-of[0]", () -> "group:tools-httpbin");
        registry.add("gateway.security.enabled", () -> "true");
        registry.add("gateway.security.provider", () -> "keycloak");
        registry.add("gateway.security.additional-providers[0]", () -> "ldap");
        registry.add("gateway.security.public-base-url", () -> "http://gateway.test");
        registry.add("gateway.security.internal.any-of[0]", () -> "role:gateway-admin");
        registry.add("gateway.security.internal.any-of[1]", () -> "group:gateway-admins");
        registry.add("gateway.security.oidc.issuer", () -> FakeKeycloak.PUBLIC_ISSUER);
        registry.add("gateway.security.oidc.internal-issuer", keycloak::internalIssuer);
        registry.add("gateway.security.oidc.client-id", () -> FakeKeycloak.CLIENT_ID);
        registry.add("gateway.security.oidc.client-secret", () -> FakeKeycloak.CLIENT_SECRET);
        registry.add("gateway.security.ldap.url", directory::url);
        registry.add("gateway.security.ldap.bind-dn", () -> TestDirectory.SERVICE_DN);
        registry.add("gateway.security.ldap.bind-password", () -> TestDirectory.SERVICE_PASSWORD);
        registry.add("gateway.security.ldap.user-search.base", () -> "ou=people," + TestDirectory.BASE);
        registry.add("gateway.security.ldap.group-search.base", () -> "ou=tools," + TestDirectory.BASE);
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
        directory.close();
    }

    @LocalServerPort
    int port;

    @Autowired
    RecordingSink audit;

    WebTestClient client;
    FakeKeycloak.User carla;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        carla = FakeKeycloak.User.of("carla", List.of("tools-httpbin"), List.of());
        upstream.reset();
        audit.clear();
    }

    @Test
    void anLdapRouteSendsTheBrowserToTheGatewayLoginForm() {
        client.get().uri("/ldap/headers").accept(MediaType.TEXT_HTML).exchange()
                .expectStatus().isFound()
                .expectHeader().location("/_gateway/login");
        client.get().uri("/ldap/headers").accept(MediaType.APPLICATION_JSON).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().doesNotExist(HttpHeaders.WWW_AUTHENTICATE);

        assertThat(upstream.lastHeaders()).isEmpty();
    }

    @Test
    void theLdapLoginOpensOnlyTheLdapRoutesInItsOwnCookie() {
        String ldapSession = ldapLogin("alice", "alice-pass", "/ldap/headers");

        client.get().uri("/ldap/headers").cookie("GW_SESSION_LDAP", ldapSession).exchange().expectStatus().isOk();
        assertThat(upstream.lastHeaders().get("X-Auth-Request-User")).containsExactly("alice");
        assertThat(upstream.lastHeaders()).doesNotContainKey(HttpHeaders.COOKIE);
        client.get().uri("/group/headers").cookie("GW_SESSION_LDAP", ldapSession).accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus().isFound()
                .expectHeader().location("/_gateway/oauth2/authorization/keycloak");
    }

    @Test
    void aKeycloakSessionDoesNotOpenTheLdapRoutes() {
        String session = keycloakLogin(carla);

        client.get().uri("/group/headers").cookie("GW_SESSION", session).exchange().expectStatus().isOk();
        client.get().uri("/ldap/headers").cookie("GW_SESSION", session).accept(MediaType.TEXT_HTML).exchange()
                .expectStatus().isFound()
                .expectHeader().location("/_gateway/login");
        assertThat(audit.awaitPath("/ldap/headers").userName()).isNull();
    }

    @Test
    void withBothSessionsEachRouteSeesTheCallerOfItsChannel() {
        String session = keycloakLogin(carla);
        String ldapSession = ldapLogin("alice", "alice-pass", "/ldap/headers");

        client.get().uri("/group/headers").cookie("GW_SESSION", session).cookie("GW_SESSION_LDAP", ldapSession)
                .exchange().expectStatus().isOk();
        assertThat(upstream.lastHeaders().get("X-Auth-Request-User")).containsExactly("carla");
        client.get().uri("/ldap/headers").cookie("GW_SESSION", session).cookie("GW_SESSION_LDAP", ldapSession)
                .exchange().expectStatus().isOk();
        assertThat(upstream.lastHeaders().get("X-Auth-Request-User")).containsExactly("alice");
        assertThat(audit.awaitPath("/ldap/headers").userName()).isEqualTo("alice");
        assertThat(audit.awaitPath("/group/headers").userName()).isEqualTo("carla");
    }

    @Test
    void theLdapLoginFormStillNeedsItsCsrfToken() {
        client.post().uri("/_gateway/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("username", "alice").with("password", "alice-pass"))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void theLogoutEndsBothSessionsOnEveryReplica() {
        String session = keycloakLogin(carla);
        String ldapSession = ldapLogin("alice", "alice-pass", "/ldap/headers");

        EntityExchangeResult<byte[]> logout = client.get().uri("/_gateway/logout")
                .cookie("GW_SESSION", session).cookie("GW_SESSION_LDAP", ldapSession)
                .exchange()
                .expectStatus().isFound()
                .expectBody().returnResult();

        assertThat(logout.getResponseHeaders().getLocation().toString())
                .startsWith(FakeKeycloak.PUBLIC_ISSUER + "/protocol/openid-connect/logout");
        assertThat(TestSupport.expires(logout, "GW_SESSION")).isTrue();
        assertThat(TestSupport.expires(logout, "GW_SESSION_LDAP")).isTrue();
        client.get().uri("/ldap/headers").cookie("GW_SESSION_LDAP", ldapSession).accept(MediaType.TEXT_HTML)
                .exchange().expectStatus().isFound().expectHeader().location("/_gateway/login");
        client.get().uri("/group/headers").cookie("GW_SESSION", session).accept(MediaType.TEXT_HTML)
                .exchange().expectStatus().isFound()
                .expectHeader().location("/_gateway/oauth2/authorization/keycloak");
    }

    @Test
    void withOnlyAnLdapSessionTheLogoutEndsOnTheLoginForm() {
        String ldapSession = ldapLogin("alice", "alice-pass", "/ldap/headers");

        client.get().uri("/_gateway/logout").cookie("GW_SESSION_LDAP", ldapSession).exchange()
                .expectStatus().isFound()
                .expectHeader().location("/_gateway/login?logout");
    }

    @Test
    void whoeverOperatesTheGatewayComesInThroughEitherChannel() {
        String admin = keycloak.accessToken(FakeKeycloak.User.serviceAccount("tools-cli", List.of("gateway-admin")));
        client.get().uri("/internal/routes").headers(h -> h.setBearerAuth(admin)).exchange().expectStatus().isOk();

        directory.addToGroup("bob", "gateway-admins");
        try {
            String operator = ldapLogin("bob", "bob-pass", "/ldap/headers", false);
            client.get().uri("/internal/routes").cookie("GW_SESSION_LDAP", operator).exchange().expectStatus().isOk();
            assertThat(audit.awaitPath("/internal/routes").userName()).isEqualTo("bob");
        } finally {
            directory.removeFromGroup("bob", "gateway-admins");
        }
        String alice = ldapLogin("alice", "alice-pass", "/ldap/headers");
        client.get().uri("/internal/routes").cookie("GW_SESSION_LDAP", alice).exchange().expectStatus().isForbidden();
    }

    @Test
    void whoamiAnswersForTheChannelAsked() {
        String session = keycloakLogin(carla);
        String ldapSession = ldapLogin("alice", "alice-pass", "/ldap/headers");

        client.get().uri("/_gateway/whoami").cookie("GW_SESSION", session).cookie("GW_SESSION_LDAP", ldapSession)
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.user").isEqualTo("carla");
        client.get().uri("/_gateway/whoami?channel=ldap").cookie("GW_SESSION", session)
                .cookie("GW_SESSION_LDAP", ldapSession)
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.user").isEqualTo("alice");
        client.get().uri("/_gateway/whoami?channel=ldap").cookie("GW_SESSION", session)
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/_gateway/whoami?channel=oauth").cookie("GW_SESSION", session)
                .exchange().expectStatus().isNotFound();
    }

    private String ldapLogin(String username, String password, String target) {
        return ldapLogin(username, password, target, true);
    }

    /** The gateway's login form, from a navigation to {@code target}; returns the ldap session cookie. */
    private String ldapLogin(String username, String password, String target, boolean allowedOnTarget) {
        EntityExchangeResult<byte[]> protectedHit = client.get().uri(target).accept(MediaType.TEXT_HTML)
                .exchange().expectStatus().isFound()
                .expectHeader().location("/_gateway/login")
                .expectBody().returnResult();
        EntityExchangeResult<String> page = client.get().uri("/_gateway/login").accept(MediaType.TEXT_HTML)
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult();
        Matcher csrf = CSRF_FIELD.matcher(page.getResponseBody());
        assertThat(csrf.find()).isTrue();
        EntityExchangeResult<byte[]> result = client.post().uri("/_gateway/login")
                .cookie("XSRF-TOKEN", TestSupport.cookie(page, "XSRF-TOKEN"))
                .cookie("REDIRECT_URI", TestSupport.cookie(protectedHit, "REDIRECT_URI"))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("username", username).with("password", password)
                        .with("_csrf", csrf.group(1)))
                .exchange()
                .expectStatus().isFound()
                .expectBody().returnResult();
        assertThat(result.getResponseHeaders().getLocation().getPath()).isEqualTo(target);
        assertThat(result.getResponseCookies().getFirst("GW_SESSION")).isNull();
        String session = TestSupport.cookie(result, "GW_SESSION_LDAP");
        assertThat(session).isNotBlank();
        if (allowedOnTarget) {
            client.get().uri(target).cookie("GW_SESSION_LDAP", session).exchange().expectStatus().isOk();
        }
        upstream.reset();
        audit.clear();
        return session;
    }

    /** A completed Keycloak login of {@code user}; returns the session cookie. */
    private String keycloakLogin(FakeKeycloak.User user) {
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
