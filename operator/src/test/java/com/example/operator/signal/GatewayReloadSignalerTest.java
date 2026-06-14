package com.example.operator.signal;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

class GatewayReloadSignalerTest {

    private WireMockServer wireMock;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterEach
    void stop() {
        if (wireMock != null && wireMock.isRunning()) {
            wireMock.stop();
        }
    }

    @Test
    void signal200CountsExactlyOnePost() {
        wireMock.stubFor(post(urlEqualTo("/internal/routes/reload"))
                .willReturn(aResponse().withStatus(200)));
        GatewayReloadSignaler signaler = new GatewayReloadSignaler(
                "http://localhost:" + wireMock.port() + "/internal/routes/reload");

        signaler.signal();

        wireMock.verify(1, postRequestedFor(urlEqualTo("/internal/routes/reload")));
    }

    @Test
    void signal503DoesNotThrow() {
        wireMock.stubFor(post(urlEqualTo("/internal/routes/reload"))
                .willReturn(aResponse().withStatus(503).withBody("unavailable")));
        GatewayReloadSignaler signaler = new GatewayReloadSignaler(
                "http://localhost:" + wireMock.port() + "/internal/routes/reload");

        signaler.signal();

        wireMock.verify(1, postRequestedFor(urlEqualTo("/internal/routes/reload")));
    }

    @Test
    void signalAgainstUnreachableServerDoesNotThrow() {
        int port = wireMock.port();
        wireMock.stop();

        GatewayReloadSignaler signaler = new GatewayReloadSignaler(
                "http://localhost:" + port + "/internal/routes/reload",
                Duration.ofMillis(500),
                Duration.ofSeconds(2));

        signaler.signal();
    }
}
