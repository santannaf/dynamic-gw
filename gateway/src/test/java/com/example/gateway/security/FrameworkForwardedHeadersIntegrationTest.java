package com.example.gateway.security;

import com.example.gateway.access.ClientAddress;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With server.forward-headers-strategy=framework, Spring takes the forwarded headers out of the
 * request before any filter runs. The route table must still tell a call through the edge from a
 * direct one, and the rate limit and the audit must still read the X-Forwarded-For.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.security.enabled=false",
        "gateway.routes.store.type=properties",
        "server.forward-headers-strategy=framework"})
@Import(FrameworkForwardedHeadersIntegrationTest.ForwardedForProbe.class)
class FrameworkForwardedHeadersIntegrationTest {

    static final List<String> forwardedFor = new CopyOnWriteArrayList<>();

    @LocalServerPort
    int port;

    @Test
    void theRouteTableStillAnswersOnlyCallersThatReachThePodDirectly() {
        client().get().uri("/internal/routes").exchange().expectStatus().isOk();
        client().get().uri("/internal/routes").header("X-Forwarded-For", "203.0.113.7")
                .exchange().expectStatus().isForbidden();
        client().get().uri("/internal/routes").header("Forwarded", "for=203.0.113.7")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void theForwardedForReachesTheGatewayAsItArrived() {
        forwardedFor.clear();
        client().get().uri("/probe").header("X-Forwarded-For", "198.51.100.1, 10.0.0.7").exchange();

        assertThat(forwardedFor).containsExactly("198.51.100.1, 10.0.0.7");
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @TestConfiguration
    static class ForwardedForProbe {

        @Bean
        WebFilter forwardedForProbe() {
            return (exchange, chain) -> {
                if (exchange.getRequest().getPath().value().equals("/probe")) {
                    forwardedFor.add(String.valueOf(ClientAddress.forwardedFor(exchange.getRequest())));
                }
                return chain.filter(exchange);
            };
        }
    }
}
