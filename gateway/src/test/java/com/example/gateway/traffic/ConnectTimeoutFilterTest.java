package com.example.gateway.traffic;

import io.netty.channel.ConnectTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectTimeoutFilterTest {

    private final ConnectTimeoutFilter filter = new ConnectTimeoutFilter();
    private final MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/a"));

    @Test
    void connectTimeoutBecomesGatewayTimeout() {
        StepVerifier.create(filter.filter(exchange,
                        ignored -> Mono.error(new ConnectTimeoutException("connection timed out after 200 ms"))))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOfSatisfying(ResponseStatusException.class,
                                status -> assertThat(status.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT))
                        .hasCauseInstanceOf(ConnectTimeoutException.class))
                .verify();
    }

    @Test
    void otherErrorsPassUntouched() {
        ConnectException refused = new ConnectException("Connection refused");

        StepVerifier.create(filter.filter(exchange, ignored -> Mono.error(refused)))
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(refused))
                .verify();
    }
}
