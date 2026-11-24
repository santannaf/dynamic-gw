package com.example.gateway.routes.store;

import com.example.shared.routes.RouteConfigEntry;
import com.example.shared.routes.RouteFilter;
import com.example.shared.routes.RouteRateLimit;
import com.example.shared.routes.RouteThrottle;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binding contract for the {@code gateway.routes.store.properties.*} side of
 * {@link RouteStoreProperties}. Asserts that the relaxed binder maps
 * {@code target-uri} (kebab-case) onto {@code targetUri} on the record, and
 * that defaults kick in when fields are omitted.
 */
class RouteStorePropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(EnablePropertiesConfig.class);

    @Test
    void bindsRoutePoliciesWithKebabCaseFieldsAndCaseSensitiveKeys() {
        runner.withPropertyValues(
                        "gateway.routes.store.properties.routes[0].id=pedidos",
                        "gateway.routes.store.properties.routes[0].path=/pedidos/**",
                        "gateway.routes.store.properties.routes[0].target-uri=http://pedidos",
                        "gateway.routes.store.properties.routes[0].rate-limit.requests=100",
                        "gateway.routes.store.properties.routes[0].rate-limit.period=1s",
                        "gateway.routes.store.properties.routes[0].rate-limit.key=header:X-Api-Key",
                        "gateway.routes.store.properties.routes[0].throttle.max-concurrent=20",
                        "gateway.routes.store.properties.routes[0].throttle.max-queue=50",
                        "gateway.routes.store.properties.routes[0].throttle.max-wait=200ms",
                        "gateway.routes.store.properties.routes[0].filters[0].name=RequestSize",
                        "gateway.routes.store.properties.routes[0].filters[0].args.maxSize=5MB",
                        "gateway.routes.store.properties.routes[0].transform.deny[0]=senha",
                        "gateway.routes.store.properties.routes[0].transform.mapping.nomeCompleto=fullName",
                        "gateway.routes.store.properties.routes[1].id=painel",
                        "gateway.routes.store.properties.routes[1].path=/painel/{id}",
                        "gateway.routes.store.properties.routes[1].aggregate.timeout=2s",
                        "gateway.routes.store.properties.routes[1].aggregate.backends[0].url=http://p/pedidos/{id}",
                        "gateway.routes.store.properties.routes[1].aggregate.backends[0].group=pedido",
                        "gateway.routes.store.properties.routes[1].aggregate.backends[1].url=http://c/clientes/{id}",
                        "gateway.routes.store.properties.routes[1].aggregate.backends[1].transform.allow[0]=nome")
                .run(ctx -> {
                    List<RouteConfigEntry> routes = ctx.getBean(RouteStoreProperties.class).getProperties().getRoutes();

                    RouteConfigEntry pedidos = routes.get(0);
                    assertThat(pedidos.rateLimit()).isEqualTo(new RouteRateLimit(100, "1s", null, "header:X-Api-Key"));
                    assertThat(pedidos.throttle()).isEqualTo(new RouteThrottle(20, 50, "200ms"));
                    assertThat(pedidos.filters()).containsExactly(new RouteFilter("RequestSize", Map.of("maxSize", "5MB")));
                    assertThat(pedidos.transform().deny()).containsExactly("senha");
                    assertThat(pedidos.transform().mapping()).containsExactly(Map.entry("nomeCompleto", "fullName"));

                    RouteConfigEntry painel = routes.get(1);
                    assertThat(painel.targetUri()).isNull();
                    assertThat(painel.aggregate().timeout()).isEqualTo("2s");
                    assertThat(painel.aggregate().backends()).hasSize(2);
                    assertThat(painel.aggregate().backends().get(0).group()).isEqualTo("pedido");
                    assertThat(painel.aggregate().backends().get(1).transform().allow()).containsExactly("nome");
                });
    }

    @Test
    void bindsRoutesListWithKebabCaseFields() {
        runner.withPropertyValues(
                        "gateway.routes.store.properties.version=v1.0.0",
                        "gateway.routes.store.properties.routes[0].id=httpbin-route",
                        "gateway.routes.store.properties.routes[0].path=/httpbin/**",
                        "gateway.routes.store.properties.routes[0].target-uri=http://httpbin.example.com:8080",
                        "gateway.routes.store.properties.routes[0].strip-prefix=1",
                        "gateway.routes.store.properties.routes[0].methods[0]=GET",
                        "gateway.routes.store.properties.routes[0].methods[1]=POST",
                        "gateway.routes.store.properties.routes[0].enabled=true",
                        "gateway.routes.store.properties.routes[1].id=anything-route",
                        "gateway.routes.store.properties.routes[1].path=/anything/**",
                        "gateway.routes.store.properties.routes[1].target-uri=http://anything.example.com:8080",
                        "gateway.routes.store.properties.routes[1].strip-prefix=0",
                        "gateway.routes.store.properties.routes[1].methods[0]=GET",
                        "gateway.routes.store.properties.routes[1].enabled=true")
                .run(ctx -> {
                    RouteStoreProperties props = ctx.getBean(RouteStoreProperties.class);
                    RouteStoreProperties.Properties cfg = props.getProperties();
                    assertThat(cfg.getVersion()).isEqualTo("v1.0.0");
                    assertThat(cfg.getRoutes()).hasSize(2);

                    RouteConfigEntry first = cfg.getRoutes().get(0);
                    assertThat(first.id()).isEqualTo("httpbin-route");
                    assertThat(first.path()).isEqualTo("/httpbin/**");
                    assertThat(first.targetUri()).isEqualTo("http://httpbin.example.com:8080");
                    assertThat(first.stripPrefix()).isEqualTo(1);
                    assertThat(first.methods()).containsExactly("GET", "POST");
                    assertThat(first.enabled()).isTrue();

                    RouteConfigEntry second = cfg.getRoutes().get(1);
                    assertThat(second.id()).isEqualTo("anything-route");
                    assertThat(second.stripPrefix()).isZero();
                    assertThat(second.methods()).containsExactly("GET");
                });
    }

    @Test
    void defaultsWhenPropertiesBlockOmitted() {
        runner.run(ctx -> {
            RouteStoreProperties.Properties cfg = ctx.getBean(RouteStoreProperties.class).getProperties();
            assertThat(cfg.getVersion()).isEqualTo("static");
            assertThat(cfg.getRoutes()).isEmpty();
        });
    }

    @Configuration
    @EnableConfigurationProperties(RouteStoreProperties.class)
    static class EnablePropertiesConfig {
    }
}
