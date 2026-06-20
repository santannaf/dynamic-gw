package com.example.gateway.routes.store;

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
    void defaultsToConfigMapProviderAndListenerWithOnlyKubernetesClient() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(RouteConfigProvider.class);
            assertThat(ctx.getBean(RouteConfigProvider.class))
                    .isInstanceOf(ConfigMapRouteConfigProvider.class);
            assertThat(ctx).hasSingleBean(RouteConfigChangeListener.class);
            assertThat(ctx.getBean(RouteConfigChangeListener.class))
                    .isInstanceOf(ConfigMapRouteConfigChangeListener.class);
            assertThat(ctx).hasSingleBean(KubernetesClient.class);
            assertThat(ctx).doesNotHaveBean(S3Client.class);
        });
    }

    @Test
    void typeS3SelectsS3ProviderAndListenerWithOnlyS3Client() {
        runner.withPropertyValues(
                        "gateway.routes.store.type=s3",
                        "gateway.routes.store.s3.bucket=test-bucket",
                        "gateway.routes.store.s3.key=snapshots/routes.yaml",
                        "gateway.routes.store.s3.region=us-east-1")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RouteConfigProvider.class);
                    assertThat(ctx.getBean(RouteConfigProvider.class))
                            .isInstanceOf(S3RouteConfigProvider.class);
                    assertThat(ctx).hasSingleBean(RouteConfigChangeListener.class);
                    assertThat(ctx.getBean(RouteConfigChangeListener.class))
                            .isInstanceOf(S3RouteConfigChangeListener.class);
                    assertThat(ctx).hasSingleBean(S3Client.class);
                    assertThat(ctx).doesNotHaveBean(KubernetesClient.class);
                });
    }

    @Test
    void typeConfigmapExplicitlySelectsConfigMapProviderAndListener() {
        runner.withPropertyValues("gateway.routes.store.type=configmap").run(ctx -> {
            assertThat(ctx.getBean(RouteConfigProvider.class))
                    .isInstanceOf(ConfigMapRouteConfigProvider.class);
            assertThat(ctx.getBean(RouteConfigChangeListener.class))
                    .isInstanceOf(ConfigMapRouteConfigChangeListener.class);
            assertThat(ctx).hasSingleBean(KubernetesClient.class);
            assertThat(ctx).doesNotHaveBean(S3Client.class);
        });
    }

    @Test
    void typePropertiesSelectsPropertiesProviderAndListenerWithoutAnyClient() {
        runner.withPropertyValues("gateway.routes.store.type=properties").run(ctx -> {
            assertThat(ctx).hasSingleBean(RouteConfigProvider.class);
            assertThat(ctx.getBean(RouteConfigProvider.class))
                    .isInstanceOf(PropertiesRouteConfigProvider.class);
            assertThat(ctx).hasSingleBean(RouteConfigChangeListener.class);
            assertThat(ctx.getBean(RouteConfigChangeListener.class))
                    .isInstanceOf(PropertiesRouteConfigChangeListener.class);
            assertThat(ctx).doesNotHaveBean(KubernetesClient.class);
            assertThat(ctx).doesNotHaveBean(S3Client.class);
        });
    }
}
