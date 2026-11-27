package com.example.gateway.traffic;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Route timeouts end to end, on routes declared in properties (v1): the route's
 * own limit wins, and a limit it leaves out is the global one of
 * spring.cloud.gateway.server.webflux.httpclient.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.gateway.server.webflux.httpclient.connect-timeout=800",
        "spring.cloud.gateway.server.webflux.httpclient.response-timeout=1500ms",
        "gateway.audit.enabled=true"
})
// Real servers live in static fields: processTestAot would start them and never exit.
@DisabledInAotMode
class RouteTimeoutsIntegrationTest {

    static final PolicyUpstream upstream = new PolicyUpstream();
    static final HangingPort hanging = new HangingPort();
    private static int index;

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        registry.add("gateway.routes.store.type", () -> "properties");
        registry.add("gateway.security.enabled", () -> "false");
        index = 0;
        route(registry, "own", upstream.baseUrl(), null, "300ms");
        route(registry, "global", upstream.baseUrl(), null, null);
        route(registry, "longer", upstream.baseUrl(), null, "5s");
        route(registry, "connect-own", hanging.baseUrl(), "200ms", null);
        route(registry, "connect-global", hanging.baseUrl(), null, null);
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
        hanging.close();
    }

    @LocalServerPort
    int port;

    @Autowired
    RecordingSink audit;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build();
    }

    @Test
    void routeResponseTimeoutAnswers504BeforeTheGlobalOne() {
        Duration took = timed(() -> client.get().uri("/own/slow?ms=2000").exchange()
                .expectStatus().isEqualTo(504));

        assertThat(took).isLessThan(Duration.ofMillis(1200));
        assertThat(audit.awaitLast(event -> "own".equals(event.routeId())).status()).isEqualTo(504);
    }

    @Test
    void routeWithoutTimeoutsGetsTheGlobalResponseTimeout() {
        client.get().uri("/global/slow?ms=100").exchange().expectStatus().isOk();

        Duration took = timed(() -> client.get().uri("/global/slow?ms=3000").exchange()
                .expectStatus().isEqualTo(504));

        assertThat(took).isBetween(Duration.ofMillis(1300), Duration.ofMillis(2800));
    }

    @Test
    void routeMayWaitLongerThanTheGlobalResponseTimeout() {
        client.get().uri("/longer/slow?ms=2000").exchange().expectStatus().isOk();
    }

    @Test
    void responseTimeoutEndsWithTheHeadersSoASlowBodyIsNotCut() {
        client.get().uri("/own/drip?ms=800").exchange()
                .expectStatus().isOk()
                .expectBody().json("{\"parte\":1,\"fim\":true}");
    }

    @Test
    void routeConnectTimeoutAnswers504() {
        assumeTrue(hanging.hangs(), "this system does not leave a connection to a full accept queue hanging");

        Duration took = timed(() -> client.get().uri("/connect-own/get").exchange()
                .expectStatus().isEqualTo(504));

        assertThat(took).isLessThan(Duration.ofMillis(700));
        assertThat(audit.awaitLast(event -> "connect-own".equals(event.routeId())).status()).isEqualTo(504);
    }

    @Test
    void routeWithoutConnectTimeoutGetsTheGlobalOne() {
        assumeTrue(hanging.hangs(), "this system does not leave a connection to a full accept queue hanging");

        Duration took = timed(() -> client.get().uri("/connect-global/get").exchange()
                .expectStatus().isEqualTo(504));

        assertThat(took).isBetween(Duration.ofMillis(700), Duration.ofMillis(1400));
    }

    private static void route(DynamicPropertyRegistry registry, String id, String target, String connect,
                              String response) {
        String prefix = "gateway.routes.store.properties.routes[" + index++ + "].";
        registry.add(prefix + "id", () -> id);
        registry.add(prefix + "path", () -> "/" + id + "/**");
        registry.add(prefix + "target-uri", () -> target);
        registry.add(prefix + "strip-prefix", () -> "1");
        if (connect != null) {
            registry.add(prefix + "timeouts.connect", () -> connect);
        }
        if (response != null) {
            registry.add(prefix + "timeouts.response", () -> response);
        }
    }

    private static Duration timed(Runnable call) {
        long start = System.nanoTime();
        call.run();
        return Duration.ofNanos(System.nanoTime() - start);
    }
}
