package com.example.gateway.security.support;

import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Upstream stand-in: answers 200 with the request it received, and remembers the last one. */
public final class EchoUpstream implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private volatile Map<String, List<String>> lastHeaders = Map.of();

    public EchoUpstream() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            exchange.getRequestHeaders().forEach(headers::put);
            lastHeaders = headers;
            ObjectNode body = JSON.createObjectNode()
                    .put("method", exchange.getRequestMethod())
                    .put("path", exchange.getRequestURI().getPath());
            byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Headers of the last request that reached the upstream (case-insensitive names). */
    public Map<String, List<String>> lastHeaders() {
        return lastHeaders;
    }

    public void reset() {
        lastHeaders = Map.of();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
