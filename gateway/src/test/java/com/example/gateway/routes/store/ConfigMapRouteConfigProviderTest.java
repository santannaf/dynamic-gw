package com.example.gateway.routes.store;

import com.example.shared.routes.RouteConfigSnapshot;
import com.example.shared.routes.SnapshotCodec;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@EnableKubernetesMockClient(crud = true)
class ConfigMapRouteConfigProviderTest {

    private KubernetesClient client;

    @Test
    void loadReturnsSnapshotFromConfigMap() {
        RouteStoreProperties properties = new RouteStoreProperties();
        properties.getConfigmap().setNamespace("platform");
        properties.getConfigmap().setConfigMapName("gateway-routes");
        properties.getConfigmap().setKey("routes.yaml");

        String yaml = """
                version: "v1"
                generatedAt: "2026-06-13T10:00:00Z"
                routes:
                  - id: httpbin-route
                    path: /httpbin/**
                    targetUri: http://httpbin.org
                    stripPrefix: 1
                    methods:
                      - GET
                """;

        client.configMaps().inNamespace("platform").resource(
                new ConfigMapBuilder()
                        .withNewMetadata().withName("gateway-routes").withNamespace("platform").endMetadata()
                        .addToData("routes.yaml", yaml)
                        .build()
        ).create();

        ConfigMapRouteConfigProvider provider = new ConfigMapRouteConfigProvider(
                client, new SnapshotCodec(), properties);

        RouteConfigSnapshot snapshot = provider.load();

        assertThat(snapshot.version()).isEqualTo("v1");
        assertThat(snapshot.routes()).hasSize(1);
        assertThat(snapshot.routes().get(0).id()).isEqualTo("httpbin-route");
        assertThat(snapshot.routes().get(0).path()).isEqualTo("/httpbin/**");
    }

    @Test
    void loadReturnsEmptySnapshotWhenConfigMapMissing() {
        RouteStoreProperties properties = new RouteStoreProperties();

        ConfigMapRouteConfigProvider provider = new ConfigMapRouteConfigProvider(
                client, new SnapshotCodec(), properties);

        RouteConfigSnapshot snapshot = provider.load();

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.routes()).isEmpty();
    }

    @Test
    void loadReturnsEmptySnapshotWhenKeyMissing() {
        RouteStoreProperties properties = new RouteStoreProperties();

        client.configMaps().inNamespace("platform").resource(
                new ConfigMapBuilder()
                        .withNewMetadata().withName("gateway-routes").withNamespace("platform").endMetadata()
                        .build()
        ).create();

        ConfigMapRouteConfigProvider provider = new ConfigMapRouteConfigProvider(
                client, new SnapshotCodec(), properties);

        RouteConfigSnapshot snapshot = provider.load();

        assertThat(snapshot.routes()).isEmpty();
    }
}
