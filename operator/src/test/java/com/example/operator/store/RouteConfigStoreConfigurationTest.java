package com.example.operator.store;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RouteConfigStoreConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(RouteConfigStoreConfiguration.class);

    @Test
    void defaultsToConfigMapPublisher() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(RouteConfigPublisher.class);
            assertThat(ctx.getBean(RouteConfigPublisher.class))
                    .isInstanceOf(ConfigMapRouteConfigPublisher.class);
            assertThat(ctx).hasSingleBean(KubernetesClient.class);
        });
    }

    @Test
    void typeS3SelectsS3PublisherAndDoesNotCreateKubernetesClient() {
        runner.withPropertyValues("operator.routes.store.type=s3").run(ctx -> {
            assertThat(ctx).hasSingleBean(RouteConfigPublisher.class);
            assertThat(ctx.getBean(RouteConfigPublisher.class))
                    .isInstanceOf(S3RouteConfigPublisher.class);
            assertThat(ctx).doesNotHaveBean(KubernetesClient.class);
        });
    }
}
