package com.example.gateway.traffic;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Upstream for the policy tests. {@code /echo/**} answers with what it got;
 * {@code /pedido/{id}}, {@code /cliente/{id}} and {@code /extra} answer JSON;
 * {@code /slow?ms=} holds the request and counts how many run at once;
 * {@code /status/{code}} answers that status; {@code /big}, {@code /big-chunked},
 * {@code /small-chunked} and {@code /text} feed the transformation limits.
 */
final class PolicyUpstream implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final String BIG_PAYLOAD = "y".repeat(8 * 1024);

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();
    private volatile String lastPath;
    private volatile String lastQuery;
    private volatile Map<String, List<String>> lastHeaders = Map.of();

    PolicyUpstream() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String lastPath() {
        return lastPath;
    }

    String lastQuery() {
        return lastQuery;
    }

    Map<String, List<String>> lastHeaders() {
        return lastHeaders;
    }

    int peakConcurrency() {
        return peak.get();
    }

    void reset() {
        lastPath = null;
        lastQuery = null;
        lastHeaders = Map.of();
        peak.set(0);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!path.startsWith("/slow")) {
            Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            exchange.getRequestHeaders().forEach(headers::put);
            lastHeaders = headers;
            lastPath = path;
            lastQuery = exchange.getRequestURI().getRawQuery();
        }
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            if (path.startsWith("/echo")) {
                ObjectNode body = JSON.createObjectNode().put("method", exchange.getRequestMethod()).put("path", path);
                exchange.getResponseHeaders().add("X-Upstream-Secret", "s3cr3t");
                json(exchange, body.toString(), false);
            } else if (path.startsWith("/pedido/")) {
                json(exchange, "{\"id\":" + idOf(path) + ",\"nome\":\"Mesa\",\"senha\":\"x\"}", false);
            } else if (path.equals("/pedido")) {
                json(exchange, "{\"id\":7,\"nome\":\"Mesa\",\"senha\":\"x\"}", false);
            } else if (path.startsWith("/cliente/")) {
                json(exchange, "{\"id\":" + idOf(path) + ",\"nome\":\"Ana\"}", false);
            } else if (path.equals("/extra")) {
                json(exchange, "{\"extra\":true}", false);
            } else if (path.startsWith("/status/")) {
                exchange.sendResponseHeaders(Integer.parseInt(path.substring("/status/".length())), -1);
            } else if (path.equals("/slow")) {
                slow(exchange);
            } else if (path.equals("/big")) {
                json(exchange, "{\"x\":1,\"payload\":\"" + BIG_PAYLOAD + "\"}", false);
            } else if (path.equals("/big-chunked")) {
                json(exchange, "{\"x\":1,\"payload\":\"" + BIG_PAYLOAD + "\"}", true);
            } else if (path.equals("/small-chunked")) {
                json(exchange, "{\"id\":1,\"x\":2}", true);
            } else if (path.equals("/text")) {
                byte[] body = "id=1".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        }
    }

    private void slow(HttpExchange exchange) throws IOException {
        peak.accumulateAndGet(running.incrementAndGet(), Math::max);
        try {
            String query = exchange.getRequestURI().getQuery();
            long millis = query == null ? 500 : Long.parseLong(query.replaceAll(".*ms=(\\d+).*", "$1"));
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            running.decrementAndGet();
        }
        json(exchange, "{\"ok\":true}", false);
    }

    private static void json(HttpExchange exchange, String body, boolean chunked) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, chunked ? 0 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String idOf(String path) {
        return path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    }
}
