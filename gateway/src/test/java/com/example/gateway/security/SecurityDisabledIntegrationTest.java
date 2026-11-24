package com.example.gateway.security;

import com.example.gateway.security.support.EchoUpstream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gateway.security.enabled=false")
// Real servers live in static fields: processTestAot would start them and never exit.
@DisabledInAotMode
class SecurityDisabledIntegrationTest {

    static final EchoUpstream upstream = new EchoUpstream();

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        SecurityTestRoutes.register(registry, upstream.baseUrl());
    }

    @AfterAll
    static void stop() {
        upstream.close();
    }

    @LocalServerPort
    int port;

    @Test
    void everyRouteIsOpenButWhatTheCallerSendsInTheGatewaysNameNeverReachesTheBackend() {
        // default-route declares no access; group-route asks for a group nobody can prove here.
        for (String path : List.of("/default/headers", "/group/headers")) {
            upstream.reset();
            client().get().uri(path)
                    .header("X-Auth-Request-User", "admin")
                    .header("x-auth-request-groups", "gateway-admins")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer for-the-backend")
                    .header(HttpHeaders.COOKIE, "GW_SESSION=forged; theme=dark; GW_SESSION_LDAP=forged; GW_RETURN_TO=x")
                    .exchange()
                    .expectStatus().isOk();

            Map<String, List<String>> received = upstream.lastHeaders();
            assertThat(received).as(path).doesNotContainKeys("X-Auth-Request-User", "X-Auth-Request-Groups");
            assertThat(received.get(HttpHeaders.AUTHORIZATION)).as(path).containsExactly("Bearer for-the-backend");
            assertThat(received.get(HttpHeaders.COOKIE)).as(path).containsExactly("theme=dark");
        }
    }

    @Test
    void theRouteTableAnswersOnlyCallersThatReachThePodDirectly() {
        client().get().uri("/internal/routes").exchange().expectStatus().isOk();
        client().get().uri("/internal/routes").header("X-Forwarded-For", "203.0.113.7")
                .exchange().expectStatus().isForbidden();
        client().get().uri("/internal/routes").header("Forwarded", "for=203.0.113.7")
                .exchange().expectStatus().isForbidden();
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }
}
