package com.example.operator.signal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayReloadSignalerTest {

    private HttpServer server;
    private AtomicInteger requestCount;
    private AtomicReference<String> lastMethod;
    private AtomicReference<String> lastPath;
    private AtomicReference<Integer> statusToReturn;
    private AtomicReference<String> bodyToReturn;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        requestCount = new AtomicInteger();
        lastMethod = new AtomicReference<>();
        lastPath = new AtomicReference<>();
        statusToReturn = new AtomicReference<>(200);
        bodyToReturn = new AtomicReference<>("");
        server.createContext("/", new RecordingHandler());
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void signal200CountsExactlyOnePost() {
        statusToReturn.set(200);
        GatewayReloadSignaler signaler = new GatewayReloadSignaler(baseUrl() + "/internal/routes/reload");

        signaler.signal();

        assertThat(requestCount).hasValue(1);
        assertThat(lastMethod).hasValue("POST");
        assertThat(lastPath).hasValue("/internal/routes/reload");
    }

    @Test
    void signal503DoesNotThrow() {
        statusToReturn.set(503);
        bodyToReturn.set("unavailable");
        GatewayReloadSignaler signaler = new GatewayReloadSignaler(baseUrl() + "/internal/routes/reload");

        signaler.signal();

        assertThat(requestCount).hasValue(1);
    }

    @Test
    void signalAgainstUnreachableServerDoesNotThrow() {
        int port = server.getAddress().getPort();
        server.stop(0);
        server = null;

        GatewayReloadSignaler signaler = new GatewayReloadSignaler(
                "http://127.0.0.1:" + port + "/internal/routes/reload",
                Duration.ofMillis(500),
                Duration.ofSeconds(2));

        signaler.signal();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private class RecordingHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            requestCount.incrementAndGet();
            lastMethod.set(exchange.getRequestMethod());
            lastPath.set(exchange.getRequestURI().getPath());
            // Drain the body so the connection can be reused/closed cleanly.
            exchange.getRequestBody().readAllBytes();
            byte[] body = bodyToReturn.get().getBytes();
            exchange.sendResponseHeaders(statusToReturn.get(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        }
    }
}
