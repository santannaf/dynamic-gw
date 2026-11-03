package com.example.gateway.traffic;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.security.enabled=false",
        "gateway.traffic.rate-limit.requests=4",
        "gateway.traffic.rate-limit.period=1m",
        "gateway.traffic.throttle.max-concurrent=1"
})
// Real servers live in static fields: processTestAot would start them and never exit.
@DisabledInAotMode
class GlobalTrafficIntegrationTest {

    static final PolicyUpstream upstream = new PolicyUpstream();

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        registry.add("gateway.routes.store.type", () -> "properties");
        for (int i = 0; i < 2; i++) {
            String prefix = "gateway.routes.store.properties.routes[" + i + "].";
            String name = i == 0 ? "a" : "b";
            registry.add(prefix + "id", () -> name);
            registry.add(prefix + "path", () -> "/" + name + "/**");
            registry.add(prefix + "target-uri", upstream::baseUrl);
            registry.add(prefix + "strip-prefix", () -> "1");
        }
    }

    @AfterAll
    static void stop() {
        upstream.close();
    }

    @LocalServerPort
    int port;

    @Test
    void ceilingsCountEveryRouteTogether() {
        WebClient web = WebClient.create("http://localhost:" + port);

        // Two at once on different routes: the global throttle lets one through (no queue).
        List<Integer> concurrent = Flux.just("/a/slow?ms=500", "/b/slow?ms=500")
                .flatMap(uri -> web.get().uri(uri)
                        .exchangeToMono(response -> response.releaseBody().thenReturn(response.statusCode().value())))
                .collectList()
                .block(Duration.ofSeconds(10));
        assertThat(concurrent).containsExactlyInAnyOrder(200, 503);

        // Both spent the global budget (4 per minute): two more, then 429 on either route.
        WebTestClient client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        client.get().uri("/a/echo").exchange().expectStatus().isOk();
        client.get().uri("/b/echo").exchange().expectStatus().isOk().expectHeader().valueEquals("RateLimit-Remaining", "0");
        client.get().uri("/a/echo").exchange().expectStatus().isEqualTo(429);
        client.get().uri("/b/echo").exchange().expectStatus().isEqualTo(429);
    }

    @Test
    void invalidGlobalCeilingStopsTheStartup() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(TrafficConfiguration.class);

        runner.withPropertyValues("gateway.traffic.rate-limit.requests=10", "gateway.traffic.rate-limit.period=soon")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("gateway.traffic: rateLimit.period"));
        runner.withPropertyValues("gateway.traffic.throttle.max-concurrent=0")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("gateway.traffic: throttle.maxConcurrent"));
        runner.run(context -> assertThat(context).hasNotFailed()
                .hasSingleBean(RateLimitFilter.class).hasSingleBean(ThrottleFilter.class)
                .hasSingleBean(ResponseTransformFilter.class).hasSingleBean(AggregationFilter.class));
    }
}
