package com.example.gateway.traffic;

import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Body of a refusal by a route policy, in the same shape as the gateway's other errors
 * (Spring Boot's {@code timestamp}, {@code path}, {@code status}, {@code error} and
 * {@code requestId}), plus a {@code message} that says why and when to retry.
 */
final class PolicyErrors {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private PolicyErrors() {
    }

    static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("timestamp", TIMESTAMP.format(Instant.now()));
        error.put("path", originalPath(exchange));
        error.put("status", status.value());
        error.put("error", status.getReasonPhrase());
        error.put("requestId", exchange.getRequest().getId());
        error.put("message", message);
        byte[] body = JSON.writeValueAsBytes(error);
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().setContentLength(body.length);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    // The path the client asked for: filters such as StripPrefix may have changed the request by now.
    private static String originalPath(ServerWebExchange exchange) {
        Set<URI> originals = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR);
        if (originals != null && !originals.isEmpty()) {
            return originals.iterator().next().getRawPath();
        }
        return exchange.getRequest().getPath().value();
    }
}
