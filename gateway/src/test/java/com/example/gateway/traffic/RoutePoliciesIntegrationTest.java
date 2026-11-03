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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every route policy end to end, on routes declared in properties (v1), the
 * same code path GatewayRoutes take after the snapshot. Security is off: the
 * policies do not depend on it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.traffic.transform.max-body-size=4KB",
        "gateway.audit.enabled=true"
})
// Real servers live in static fields: processTestAot would start them and never exit.
@DisabledInAotMode
class RoutePoliciesIntegrationTest {

    static final PolicyUpstream upstream = new PolicyUpstream();
    private static int index;

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        String base = upstream.baseUrl();
        registry.add("gateway.routes.store.type", () -> "properties");
        registry.add("gateway.security.enabled", () -> "false");
        index = 0;

        String limited = route(registry, "limited", "/limited/**", base, 1);
        registry.add(limited + "rate-limit.requests", () -> "3");
        registry.add(limited + "rate-limit.period", () -> "1m");

        String perKey = route(registry, "per-key", "/per-key/**", base, 1);
        registry.add(perKey + "rate-limit.requests", () -> "1");
        registry.add(perKey + "rate-limit.period", () -> "1m");
        registry.add(perKey + "rate-limit.key", () -> "header:X-Api-Key");

        String throttled = route(registry, "throttled", "/throttled/**", base, 1);
        registry.add(throttled + "throttle.max-concurrent", () -> "2");
        registry.add(throttled + "throttle.max-queue", () -> "1");
        registry.add(throttled + "throttle.max-wait", () -> "3s");

        String filters = route(registry, "filters", "/filters/**", base, 0);
        filter(registry, filters, 0, "RewritePath", "regexp", "/filters/(?<rest>.*)", "replacement", "/echo/rewritten/${rest}");
        filter(registry, filters, 1, "AddRequestHeader", "name", "X-Source", "value", "gateway");
        filter(registry, filters, 2, "RemoveRequestHeader", "name", "X-Debug");
        filter(registry, filters, 3, "SetRequestHeader", "name", "X-Mode", "value", "replaced");
        filter(registry, filters, 4, "AddRequestParameter", "name", "origem", "value", "borda");
        filter(registry, filters, 5, "RemoveRequestParameter", "name", "debug");
        filter(registry, filters, 6, "AddResponseHeader", "name", "X-Policy", "value", "filters");
        filter(registry, filters, 7, "RemoveResponseHeader", "name", "X-Upstream-Secret");

        String size = route(registry, "size", "/size/**", base, 1);
        filter(registry, size, 0, "RequestSize", "maxSize", "1KB");

        String transform = route(registry, "transform", "/transform/**", base, 1);
        filter(registry, transform, 0, "SetPath", "template", "/pedido");
        registry.add(transform + "transform.allow[0]", () -> "id");
        registry.add(transform + "transform.allow[1]", () -> "nome");
        registry.add(transform + "transform.mapping.nome", () -> "name");
        registry.add(transform + "transform.group", () -> "pedido");

        for (String source : List.of("big", "big-chunked", "small-chunked", "text")) {
            String route = route(registry, "transform-" + source, "/transform-" + source + "/**", base, 1);
            filter(registry, route, 0, "SetPath", "template", "/" + source);
            registry.add(route + "transform.deny[0]", () -> "x");
        }

        aggregate(registry, "painel", "1s", base + "/pedido/{id}", "pedido", base + "/cliente/{id}", "cliente");
        registry.add(previous() + "aggregate.backends[0].transform.allow[0]", () -> "id");
        registry.add(previous() + "aggregate.backends[0].transform.allow[1]", () -> "nome");
        aggregate(registry, "parcial", "1s", base + "/pedido/{id}", "pedido", base + "/status/503", "estoque");
        aggregate(registry, "falha", "1s", base + "/status/500", "a", base + "/status/502", "b");
        aggregate(registry, "lento", "300ms", base + "/slow?ms=2000", "a", base + "/slow?ms=2000", "b");
        aggregate(registry, "merge", "1s", base + "/pedido/{id}", null, base + "/extra", null);
        String agregadoTransformado = aggregate(registry, "resumo", "1s", base + "/pedido/{id}", "pedido",
                base + "/cliente/{id}", "cliente");
        registry.add(agregadoTransformado + "transform.allow[0]", () -> "cliente");
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
        upstream.reset();
    }

    @Test
    void catalogFiltersChangeRequestAndResponse() {
        client.get().uri("/filters/abc?debug=1&keep=yes")
                .header("X-Debug", "1")
                .header("X-Mode", "original")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Policy", "filters")
                .expectHeader().doesNotExist("X-Upstream-Secret");

        assertThat(upstream.lastPath()).isEqualTo("/echo/rewritten/abc");
        assertThat(upstream.lastHeaders().get("X-Source")).containsExactly("gateway");
        assertThat(upstream.lastHeaders().get("X-Mode")).containsExactly("replaced");
        assertThat(upstream.lastHeaders()).doesNotContainKey("X-Debug");
        assertThat(upstream.lastQuery()).contains("keep=yes").contains("origem=borda").doesNotContain("debug");
    }

    @Test
    void requestSizeRefusesLargeBodies() {
        client.post().uri("/size/echo").bodyValue("z".repeat(2048)).exchange().expectStatus().isEqualTo(413);
        client.post().uri("/size/echo").bodyValue("small").exchange().expectStatus().isOk();
    }

    @Test
    void rateLimitServesTheBudgetThenRefusesWith429() {
        for (int remaining = 2; remaining >= 0; remaining--) {
            client.get().uri("/limited/echo").exchange()
                    .expectStatus().isOk()
                    .expectHeader().valueEquals("RateLimit-Limit", "3")
                    .expectHeader().valueEquals("RateLimit-Remaining", String.valueOf(remaining))
                    .expectHeader().valueEquals("RateLimit-Policy", "3;w=60");
        }
        client.get().uri("/limited/echo").exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("RateLimit-Remaining", "0")
                .expectHeader().value(HttpHeaders.RETRY_AFTER, value -> assertThat(Long.parseLong(value)).isBetween(1L, 20L))
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(429)
                .jsonPath("$.error").isEqualTo("Too Many Requests")
                .jsonPath("$.path").isEqualTo("/limited/echo")
                .jsonPath("$.requestId").isNotEmpty()
                .jsonPath("$.timestamp").isNotEmpty()
                .jsonPath("$.message").value(message -> assertThat((String) message)
                        .startsWith("Rate limit of this route reached: 3 requests every 1m. Retry after "));

        assertThat(audit.awaitLast(event -> "rate_limited".equals(event.reason())).routeId()).isEqualTo("limited");
    }

    @Test
    void perCallerBudgetsAreIndependent() {
        client.get().uri("/per-key/echo").header("X-Api-Key", "a").exchange().expectStatus().isOk();
        client.get().uri("/per-key/echo").header("X-Api-Key", "a").exchange().expectStatus().isEqualTo(429);
        client.get().uri("/per-key/echo").header("X-Api-Key", "b").exchange().expectStatus().isOk();
    }

    @Test
    void throttleRunsTheLimitQueuesOneAndRefusesTheRest() {
        WebClient web = WebClient.create("http://localhost:" + port);

        List<String> answers = Flux.range(0, 4)
                .flatMap(i -> web.get().uri("/throttled/slow?ms=700")
                        .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                                .map(body -> response.statusCode().value() + " " + body)))
                .collectList()
                .block(Duration.ofSeconds(15));

        assertThat(answers).extracting(answer -> answer.substring(0, 3)).containsExactlyInAnyOrder("200", "200", "200", "503");
        assertThat(answers).filteredOn(answer -> answer.startsWith("503")).singleElement().asString()
                .contains("\"status\":503", "\"error\":\"Service Unavailable\"", "\"path\":\"/throttled/slow\"", "Retry after 1s.");
        assertThat(upstream.peakConcurrency()).isEqualTo(2);
        assertThat(audit.awaitLast(event -> "throttled".equals(event.reason())).routeId()).isEqualTo("throttled");
    }

    @Test
    void transformReshapesTheJsonAndFixesTheLength() {
        String body = client.get().uri("/transform/x").exchange()
                .expectStatus().isOk()
                .expectHeader().contentLength(33)
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).isEqualTo("{\"pedido\":{\"id\":7,\"name\":\"Mesa\"}}");
    }

    @Test
    void transformLeavesLargeOrNonJsonBodiesAlone() {
        assertThat(get("/transform-big/x")).contains("\"x\":1").contains(PolicyUpstream.BIG_PAYLOAD);
        assertThat(get("/transform-big-chunked/x")).contains("\"x\":1").contains(PolicyUpstream.BIG_PAYLOAD);
        assertThat(get("/transform-small-chunked/x")).isEqualTo("{\"id\":1}");
        assertThat(get("/transform-text/x")).isEqualTo("id=1");
    }

    @Test
    void aggregateMergesBackendsUnderTheirGroups() {
        client.get().uri("/painel/42").header("X-Request-Id", "r-1").header("Cookie", "theme=dark").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(AggregationFilter.COMPLETED_HEADER, "true")
                .expectBody().json("{\"pedido\":{\"id\":42,\"nome\":\"Mesa\"},\"cliente\":{\"id\":42,\"nome\":\"Ana\"}}", true);

        assertThat(upstream.lastHeaders().get("X-Request-Id")).containsExactly("r-1");
        assertThat(upstream.lastHeaders()).doesNotContainKey("Cookie");
    }

    @Test
    void aggregateWithoutGroupsMergesTheFields() {
        client.get().uri("/merge/5").exchange()
                .expectStatus().isOk()
                .expectBody().json("{\"id\":5,\"nome\":\"Mesa\",\"senha\":\"x\",\"extra\":true}", true);
    }

    @Test
    void routeTransformAppliesToTheAggregatedAnswer() {
        client.get().uri("/resumo/3").exchange()
                .expectStatus().isOk()
                .expectBody().json("{\"cliente\":{\"id\":3,\"nome\":\"Ana\"}}", true);
    }

    @Test
    void failedBackendsAreLeftOutAndSignalled() {
        client.get().uri("/parcial/42").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(AggregationFilter.COMPLETED_HEADER, "false")
                .expectBody().json("{\"pedido\":{\"id\":42,\"nome\":\"Mesa\",\"senha\":\"x\"}}", true);

        client.get().uri("/falha/1").exchange()
                .expectStatus().isEqualTo(502)
                .expectHeader().valueEquals(AggregationFilter.COMPLETED_HEADER, "false")
                .expectBody()
                .jsonPath("$.status").isEqualTo(502)
                .jsonPath("$.path").isEqualTo("/falha/1")
                .jsonPath("$.message").isEqualTo("No backend of the aggregation answered successfully.");
        client.get().uri("/lento/1").exchange()
                .expectStatus().isEqualTo(504)
                .expectBody()
                .jsonPath("$.error").isEqualTo("Gateway Timeout")
                .jsonPath("$.message").isEqualTo("No backend of the aggregation answered in time.");
    }

    @Test
    void aggregateAnswersGetOnly() {
        client.post().uri("/painel/42").exchange().expectStatus().isNotFound();
    }

    private String get(String uri) {
        return client.get().uri(uri).exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
    }

    private static String route(DynamicPropertyRegistry registry, String id, String path, String target, int strip) {
        String prefix = "gateway.routes.store.properties.routes[" + index++ + "].";
        registry.add(prefix + "id", () -> id);
        registry.add(prefix + "path", () -> path);
        if (target != null) {
            registry.add(prefix + "target-uri", () -> target);
        }
        registry.add(prefix + "strip-prefix", () -> String.valueOf(strip));
        registry.add(prefix + "enabled", () -> "true");
        return prefix;
    }

    private static String previous() {
        return "gateway.routes.store.properties.routes[" + (index - 1) + "].";
    }

    private static void filter(DynamicPropertyRegistry registry, String route, int position, String name,
                               String... args) {
        String prefix = route + "filters[" + position + "].";
        registry.add(prefix + "name", () -> name);
        for (int i = 0; i < args.length; i += 2) {
            String value = args[i + 1];
            registry.add(prefix + "args." + args[i], () -> value);
        }
    }

    private static String aggregate(DynamicPropertyRegistry registry, String id, String timeout,
                                    String firstUrl, String firstGroup, String secondUrl, String secondGroup) {
        String prefix = route(registry, id, "/" + id + "/{id}", null, 1);
        registry.add(prefix + "aggregate.timeout", () -> timeout);
        registry.add(prefix + "aggregate.backends[0].url", () -> firstUrl);
        if (firstGroup != null) {
            registry.add(prefix + "aggregate.backends[0].group", () -> firstGroup);
        }
        registry.add(prefix + "aggregate.backends[1].url", () -> secondUrl);
        if (secondGroup != null) {
            registry.add(prefix + "aggregate.backends[1].group", () -> secondGroup);
        }
        return prefix;
    }
}
