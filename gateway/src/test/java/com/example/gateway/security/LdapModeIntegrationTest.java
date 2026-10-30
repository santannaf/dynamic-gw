package com.example.gateway.security;

import com.example.gateway.audit.AuditEvent;
import com.example.gateway.security.support.EchoUpstream;
import com.example.gateway.security.support.TestDirectory;
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
import org.springframework.web.reactive.function.BodyInserters;

import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Provider {@code ldap} end to end against an in-memory directory and an echo upstream. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Real servers live in static fields: processTestAot would start them and never exit.
@DisabledInAotMode
class LdapModeIntegrationTest {

    static final EchoUpstream upstream = new EchoUpstream();
    static final TestDirectory directory = new TestDirectory();
    private static final Pattern CSRF_FIELD = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SecurityTestRoutes.register(registry, upstream.baseUrl());
        registry.add("gateway.security.enabled", () -> "true");
        registry.add("gateway.security.provider", () -> "ldap");
        registry.add("gateway.security.public-base-url", () -> "http://gateway.test");
        registry.add("gateway.security.session.keys[0].id", () -> "k1");
        registry.add("gateway.security.session.keys[0].secret", () -> TestSupport.KEY_1);
        registry.add("gateway.security.session.cookie-secure", () -> "false");
        registry.add("gateway.security.ldap.url", directory::url);
        registry.add("gateway.security.ldap.bind-dn", () -> TestDirectory.SERVICE_DN);
        registry.add("gateway.security.ldap.bind-password", () -> TestDirectory.SERVICE_PASSWORD);
        registry.add("gateway.security.ldap.user-search.base", () -> "ou=people," + TestDirectory.BASE);
        registry.add("gateway.security.ldap.group-search.base", () -> "ou=tools," + TestDirectory.BASE);
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
        directory.close();
    }

    @LocalServerPort
    int port;

    @Autowired
    RecordingSink audit;

    @Autowired
    MutableClock clock;

    WebTestClient client;

    private static final java.util.concurrent.atomic.AtomicInteger CLIENTS = new java.util.concurrent.atomic.AtomicInteger();

    @BeforeEach
    void setUp() {
        // Each test looks like a different client address, so the per-address
        // login counter of one test does not throttle the next.
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader("X-Forwarded-For", "10.1.0." + CLIENTS.incrementAndGet())
                .build();
        upstream.reset();
        audit.clear();
        clock.reset();
    }

    @Test
    void loginPageCarriesACsrfTokenScopedToTheLoginPath() {
        EntityExchangeResult<String> page = client.get().uri("/_gateway/login")
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Frame-Options", "DENY")
                .expectBody(String.class).returnResult();

        assertThat(page.getResponseBody()).contains("<form method=\"post\" action=\"/_gateway/login\">");
        assertThat(CSRF_FIELD.matcher(page.getResponseBody()).find()).isTrue();
        assertThat(page.getResponseCookies().getFirst("XSRF-TOKEN").getPath()).isEqualTo("/_gateway/login");
    }

    @Test
    void validCredentialsCreateASessionWithDirectoryGroups() {
        String session = login("alice", "alice-pass", "/group/anything");

        client.get().uri("/group/anything")
                .cookie("GW_SESSION", session)
                .exchange()
                .expectStatus().isOk();

        assertThat(upstream.lastHeaders().get("X-Auth-Request-User")).containsExactly("alice");
        assertThat(upstream.lastHeaders().get("X-Auth-Request-Groups"))
                .as("only groups under the application's OU become authorities")
                .containsExactly("tools-httpbin");
        AuditEvent event = audit.awaitPath("/group/anything");
        assertThat(event.userName()).isEqualTo("alice");
        assertThat(event.matchedAuthority()).isEqualTo("group:tools-httpbin");
    }

    @Test
    void loginStartedByAFrontGoesToTheFormAndKeepsTheReturnTarget() {
        client.get().uri("/_gateway/login/start?returnTo=/group/anything")
                .exchange()
                .expectStatus().isFound()
                .expectHeader().location("/_gateway/login")
                .expectCookie().exists("GW_RETURN_TO");
    }

    @Test
    void userOutsideTheGroupIsForbidden() {
        String session = login("bob", "bob-pass", "/group/anything");

        client.get().uri("/group/anything").cookie("GW_SESSION", session).exchange().expectStatus().isForbidden();
    }

    @Test
    void wrongPasswordAndUnknownUserLookTheSame() {
        postLogin("alice", "wrong").expectStatus().isFound().expectHeader().location("/_gateway/login?error");
        postLogin("nobody", "whatever").expectStatus().isFound().expectHeader().location("/_gateway/login?error");

        audit.awaitLast(e -> "auth.login".equals(e.action()) && "nobody".equals(e.userName()));
        List<AuditEvent> failures = audit.matching(e -> "auth.login".equals(e.action()));
        assertThat(failures).extracting(AuditEvent::reason).containsOnly("unauthenticated");
        assertThat(failures).extracting(AuditEvent::userName).contains("alice", "nobody");
    }

    @Test
    void emptyPasswordNeverReachesTheDirectory() {
        int bindsBefore = directory.userBinds();

        postLogin("alice", "").expectStatus().isFound().expectHeader().location("/_gateway/login?error");

        assertThat(directory.userBinds()).isEqualTo(bindsBefore);
    }

    @Test
    void disabledAccountCannotLogIn() {
        postLogin("dave", "dave-pass").expectStatus().isFound().expectHeader().location("/_gateway/login?error");
    }

    @Test
    void loginWithoutCsrfTokenIsRejected() {
        int bindsBefore = directory.userBinds();

        client.post().uri("/_gateway/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("username", "alice").with("password", "alice-pass"))
                .exchange()
                .expectStatus().isForbidden();

        assertThat(directory.userBinds()).isEqualTo(bindsBefore);
    }

    @Test
    void repeatedFailuresAreThrottled() {
        for (int i = 0; i < 5; i++) {
            postLogin("throttled-user", "wrong-" + i).expectStatus().isFound();
        }
        int bindsBefore = directory.userBinds();

        postLogin("throttled-user", "wrong-again").expectStatus().isEqualTo(429);

        assertThat(directory.userBinds()).isEqualTo(bindsBefore);
        assertThat(audit.awaitLast(e -> "throttled".equals(e.reason())).userName()).isEqualTo("throttled-user");
    }

    @Test
    void authorizationHeaderIsNotInterpretedInLdapMode() {
        client.get().uri("/public/anything")
                .header(HttpHeaders.AUTHORIZATION, "Bearer some-backend-token")
                .exchange()
                .expectStatus().isOk();
        assertThat(upstream.lastHeaders().get("Authorization")).containsExactly("Bearer some-backend-token");

        client.get().uri("/default/anything")
                .header(HttpHeaders.AUTHORIZATION, "Bearer some-backend-token")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().doesNotExist(HttpHeaders.WWW_AUTHENTICATE);
    }

    @Test
    void groupRemovalTakesEffectAfterRevalidation() {
        String session = login("alice", "alice-pass", "/group/anything");
        directory.removeFromGroup("alice", "tools-httpbin");
        try {
            // The replica still holds a valid cache entry: no directory lookup yet.
            client.get().uri("/group/anything").cookie("GW_SESSION", session).exchange().expectStatus().isOk();

            clock.advance(Duration.ofMinutes(6));
            client.get().uri("/group/anything").cookie("GW_SESSION", session).exchange().expectStatus().isForbidden();
        } finally {
            directory.addToGroup("alice", "tools-httpbin");
        }
    }

    @Test
    void directoryDownGives503OnProtectedRoutesOnly() {
        String session = login("alice", "alice-pass", "/group/anything");
        directory.stop();
        try {
            clock.advance(Duration.ofMinutes(6));
            client.get().uri("/group/anything").cookie("GW_SESSION", session).accept(MediaType.TEXT_HTML)
                    .exchange().expectStatus().isEqualTo(503);
            client.get().uri("/public/anything").cookie("GW_SESSION", session).exchange().expectStatus().isOk();
        } finally {
            directory.start();
        }
    }

    @Test
    void sessionPastItsMaxAgeIsGone() {
        String session = login("alice", "alice-pass", "/group/anything");
        clock.advance(Duration.ofHours(11));

        EntityExchangeResult<byte[]> result = client.get().uri("/group/anything").cookie("GW_SESSION", session)
                .exchange().expectStatus().isUnauthorized().expectBody().returnResult();

        assertThat(TestSupport.expires(result, "GW_SESSION")).isTrue();
    }

    @Test
    void logoutReturnsToTheLoginPage() {
        String session = login("alice", "alice-pass", "/group/anything");

        EntityExchangeResult<byte[]> result = client.get().uri("/_gateway/logout").cookie("GW_SESSION", session)
                .exchange().expectStatus().isFound().expectBody().returnResult();

        assertThat(result.getResponseHeaders().getLocation()).hasToString("/_gateway/login?logout");
        assertThat(TestSupport.expires(result, "GW_SESSION")).isTrue();
    }

    private WebTestClient.ResponseSpec postLogin(String username, String password) {
        EntityExchangeResult<String> page = client.get().uri("/_gateway/login").accept(MediaType.TEXT_HTML)
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult();
        Matcher csrf = CSRF_FIELD.matcher(page.getResponseBody());
        assertThat(csrf.find()).isTrue();
        String csrfCookie = TestSupport.cookie(page, "XSRF-TOKEN");
        return client.post().uri("/_gateway/login")
                .cookie("XSRF-TOKEN", csrfCookie)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("username", username)
                        .with("password", password)
                        .with("_csrf", csrf.group(1)))
                .exchange();
    }

    private String login(String username, String password, String target) {
        EntityExchangeResult<byte[]> protectedHit = client.get().uri(target).accept(MediaType.TEXT_HTML)
                .exchange().expectStatus().isFound()
                .expectHeader().location("/_gateway/login")
                .expectBody().returnResult();
        String savedRequest = TestSupport.cookie(protectedHit, "REDIRECT_URI");

        EntityExchangeResult<String> page = client.get().uri("/_gateway/login").accept(MediaType.TEXT_HTML)
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult();
        Matcher csrf = CSRF_FIELD.matcher(page.getResponseBody());
        assertThat(csrf.find()).isTrue();

        EntityExchangeResult<byte[]> result = client.post().uri("/_gateway/login")
                .cookie("XSRF-TOKEN", TestSupport.cookie(page, "XSRF-TOKEN"))
                .cookie("REDIRECT_URI", savedRequest)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("username", username)
                        .with("password", password)
                        .with("_csrf", csrf.group(1)))
                .exchange()
                .expectStatus().isFound()
                .expectBody().returnResult();
        assertThat(result.getResponseHeaders().getLocation().getPath()).isEqualTo(target);
        String session = TestSupport.cookie(result, "GW_SESSION");
        assertThat(session).isNotBlank();
        AuditEvent loginEvent = audit.awaitLast(e -> "auth.login".equals(e.action()) && e.allowed());
        assertThat(loginEvent.userName()).isEqualTo(username);
        upstream.reset();
        audit.clear();
        return session;
    }
}
