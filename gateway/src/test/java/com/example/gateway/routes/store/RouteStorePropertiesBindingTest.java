package com.example.gateway.routes.store;

import com.example.shared.routes.RouteConfigEntry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

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
