package com.example.gateway.routes.store;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class RouteConfigStoreConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(RouteConfigStoreConfiguration.class);

    @Test
    void defaultsToConfigMapProviderAndCreatesKubernetesClient() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(RouteConfigProvider.class);
            assertThat(ctx.getBean(RouteConfigProvider.class))
                    .isInstanceOf(ConfigMapRouteConfigProvider.class);
            assertThat(ctx).hasSingleBean(KubernetesClient.class);
        });
    }

    @Test
    void typeS3SelectsS3ProviderAndDoesNotCreateKubernetesClient() {
        runner.withPropertyValues("gateway.routes.store.type=s3").run(ctx -> {
            assertThat(ctx).hasSingleBean(RouteConfigProvider.class);
            assertThat(ctx.getBean(RouteConfigProvider.class))
                    .isInstanceOf(S3RouteConfigProvider.class);
            assertThat(ctx).doesNotHaveBean(KubernetesClient.class);
        });
    }

    @Test
    void typeConfigmapExplicitlySelectsConfigMapProvider() {
        runner.withPropertyValues("gateway.routes.store.type=configmap").run(ctx -> {
            assertThat(ctx.getBean(RouteConfigProvider.class))
                    .isInstanceOf(ConfigMapRouteConfigProvider.class);
        });
    }
}
