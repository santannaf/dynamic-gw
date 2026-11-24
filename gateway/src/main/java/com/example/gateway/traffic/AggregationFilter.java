package com.example.gateway.traffic;

import com.example.gateway.routing.RouteDefinitionMapper;
import com.example.shared.routes.RouteAggregate;
import com.example.shared.routes.RouteBackend;
import com.example.shared.routes.RoutePolicyRules;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.RouteToRequestUrlFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Answers a route with {@code aggregate}: calls every backend in parallel with
 * the request's headers (identity included, as the access check left them),
 * and merges the JSON answers, each under its {@code group} or field by field.
 * A backend that fails or runs out of time is left out and the answer says so
 * in {@code X-Gateway-Completed: false}; when all fail, 504 if they all timed
 * out, 502 otherwise. The route's {@code transform} applies to the merged answer.
 */
public class AggregationFilter implements GlobalFilter, Ordered {

    public static final int ORDER = RouteToRequestUrlFilter.ROUTE_TO_URL_FILTER_ORDER + 1;
    public static final String COMPLETED_HEADER = "X-Gateway-Completed";

    // Hop-by-hop headers, headers about a body that is not sent, Host (each backend has
    // its own), Accept-Encoding (the answers are merged as plain JSON) and Cookie (the
    // caller's session with the gateway stays at the gateway).
    private static final Set<String> NOT_FORWARDED = Set.of("host", "content-length", "content-type",
            "transfer-encoding", "connection", "keep-alive", "upgrade", "te", "trailer", "proxy-connection",
            "proxy-authorization", "expect", "accept-encoding", "cookie");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}/]+)}");

    private final WebClient client;
    private final Duration defaultTimeout;
    private final JsonMapper json = JsonMapper.builder().build();

    public AggregationFilter(WebClient client, Duration defaultTimeout) {
        this.client = client;
        this.defaultTimeout = defaultTimeout;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        RouteAggregate aggregate = route != null
                && route.getMetadata().get(RouteDefinitionMapper.AGGREGATE_METADATA_KEY) instanceof RouteAggregate policy
                ? policy : null;
        if (aggregate == null) {
            return chain.filter(exchange);
        }
        // The routing filters must not try the route's no://op upstream.
        ServerWebExchangeUtils.setAlreadyRouted(exchange);
        Map<String, String> variables = ServerWebExchangeUtils.getUriTemplateVariables(exchange);
        String query = exchange.getRequest().getURI().getRawQuery();
        HttpHeaders headers = forwardedHeaders(exchange.getRequest().getHeaders());
        Duration declared = RoutePolicyRules.parseDuration(aggregate.timeout());
        Duration timeout = declared != null ? declared : defaultTimeout;

        List<Mono<Part>> calls = aggregate.backends().stream()
                .map(backend -> call(backend, variables, query, headers, timeout))
                .toList();
        return Mono.zip(calls, results -> Arrays.stream(results).map(Part.class::cast).toList())
                .flatMap(parts -> answer(exchange, parts));
    }

    private Mono<Part> call(RouteBackend backend, Map<String, String> variables, String query,
                            HttpHeaders headers, Duration timeout) {
        URI uri;
        try {
            uri = URI.create(withQuery(expand(backend.url(), variables), query));
        } catch (IllegalArgumentException unresolved) {
            return Mono.just(Part.failed(backend, false));
        }
        return client.get().uri(uri)
                .headers(target -> target.addAll(headers))
                .accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(response -> response.statusCode().is2xxSuccessful()
                        ? response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0])
                        : response.releaseBody().then(Mono.error(
                                new IllegalStateException("backend answered " + response.statusCode()))))
                .timeout(timeout)
                .map(body -> Part.ok(backend, parse(body, backend)))
                .onErrorResume(error -> Mono.just(Part.failed(backend, error instanceof TimeoutException)));
    }

    private JsonNode parse(byte[] body, RouteBackend backend) {
        JsonNode document = body.length == 0 ? json.createObjectNode() : json.readTree(body);
        return backend.transform() == null ? document : JsonTransformer.apply(document, backend.transform());
    }

    private Mono<Void> answer(ServerWebExchange exchange, List<Part> parts) {
        List<Part> answered = parts.stream().filter(Part::answered).toList();
        ServerHttpResponse response = exchange.getResponse();
        response.getHeaders().set(COMPLETED_HEADER, String.valueOf(answered.size() == parts.size()));
        if (answered.isEmpty()) {
            return parts.stream().allMatch(Part::timedOut)
                    ? PolicyErrors.write(exchange, HttpStatus.GATEWAY_TIMEOUT, "No backend of the aggregation answered in time.")
                    : PolicyErrors.write(exchange, HttpStatus.BAD_GATEWAY, "No backend of the aggregation answered successfully.");
        }
        ObjectNode merged = json.createObjectNode();
        for (Part part : answered) {
            if (part.backend().group() != null) {
                merged.set(part.backend().group(), part.body());
            } else if (part.body().isObject()) {
                merged.setAll((ObjectNode) part.body());
            } else {
                // A list without a group goes under "collection", as in KrakenD.
                merged.set("collection", part.body());
            }
        }
        byte[] body = json.writeValueAsBytes(merged);
        response.setStatusCode(HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().setContentLength(body.length);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    private static HttpHeaders forwardedHeaders(HttpHeaders request) {
        HttpHeaders headers = new HttpHeaders();
        request.forEach((name, values) -> {
            if (!NOT_FORWARDED.contains(name.toLowerCase(Locale.ROOT))) {
                headers.addAll(name, values);
            }
        });
        return headers;
    }

    private static String expand(String url, Map<String, String> variables) {
        Matcher matcher = PATH_VARIABLE.matcher(url);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = variables.get(matcher.group(1));
            String replacement = value == null ? matcher.group() : UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        return matcher.appendTail(out).toString();
    }

    private static String withQuery(String url, String query) {
        if (query == null || query.isEmpty()) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    private record Part(RouteBackend backend, JsonNode body, boolean answered, boolean timedOut) {
        static Part ok(RouteBackend backend, JsonNode body) {
            return new Part(backend, body, true, false);
        }

        static Part failed(RouteBackend backend, boolean timedOut) {
            return new Part(backend, null, false, timedOut);
        }
    }
}
