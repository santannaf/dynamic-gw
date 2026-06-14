package com.example.operator.store;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;

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
            assertThat(ctx).hasSingleBean(S3Client.class);
        });
    }

    @Test
    void typeS3SelectsS3PublisherKeepingBothClients() {
        runner.withPropertyValues(
                        "operator.routes.store.type=s3",
                        "operator.routes.store.s3.bucket=test-bucket",
                        "operator.routes.store.s3.key=snapshots/routes.yaml",
                        "operator.routes.store.s3.region=us-east-1")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RouteConfigPublisher.class);
                    assertThat(ctx.getBean(RouteConfigPublisher.class))
                            .isInstanceOf(S3RouteConfigPublisher.class);
                    assertThat(ctx).hasSingleBean(KubernetesClient.class);
                    assertThat(ctx).hasSingleBean(S3Client.class);
                });
    }
}
