package com.example.operator.store;

import com.example.shared.routes.RouteConfigEntry;
import com.example.shared.routes.RouteConfigSnapshot;
import com.example.shared.routes.SnapshotCodec;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnableKubernetesMockClient(crud = true)
class ConfigMapRouteConfigPublisherTest {

    private KubernetesClient client;

    @Test
    void publishCreatesConfigMapOnFirstCall() {
        RouteStoreProperties properties = new RouteStoreProperties();
        SnapshotCodec codec = new SnapshotCodec();
        ConfigMapRouteConfigPublisher publisher = new ConfigMapRouteConfigPublisher(client, codec, properties);

        RouteConfigSnapshot snapshot = new RouteConfigSnapshot(
                "v1",
                Instant.parse("2026-06-13T10:00:00Z"),
                List.of(new RouteConfigEntry("httpbin-route", "/httpbin/**", "http://httpbin.org", 1, List.of("GET"), true))
        );

        publisher.publish(snapshot);

        ConfigMap cm = client.configMaps().inNamespace("platform").withName("gateway-routes").get();
        assertThat(cm).isNotNull();
        assertThat(cm.getData().get("routes.yaml")).contains("httpbin-route");
        assertThat(cm.getData().get("routes.yaml")).contains("http://httpbin.org");
    }

    @Test
    void publishUpdatesExistingConfigMap() {
        RouteStoreProperties properties = new RouteStoreProperties();
        SnapshotCodec codec = new SnapshotCodec();
        ConfigMapRouteConfigPublisher publisher = new ConfigMapRouteConfigPublisher(client, codec, properties);

        publisher.publish(new RouteConfigSnapshot(
                "v1", Instant.now(),
                List.of(new RouteConfigEntry("first", "/first/**", "http://first", 1, null, true))
        ));
        publisher.publish(new RouteConfigSnapshot(
                "v2", Instant.now(),
                List.of(new RouteConfigEntry("second", "/second/**", "http://second", 1, null, true))
        ));

        ConfigMap cm = client.configMaps().inNamespace("platform").withName("gateway-routes").get();
        assertThat(cm.getMetadata().getNamespace()).isEqualTo("platform");
        assertThat(cm.getMetadata().getName()).isEqualTo("gateway-routes");
        assertThat(cm.getData().get("routes.yaml"))
                .contains("second")
                .doesNotContain("first");
    }

    @Test
    void emptySnapshotIsPublishedWithoutFailure() {
        RouteStoreProperties properties = new RouteStoreProperties();
        SnapshotCodec codec = new SnapshotCodec();
        ConfigMapRouteConfigPublisher publisher = new ConfigMapRouteConfigPublisher(client, codec, properties);

        publisher.publish(new RouteConfigSnapshot("empty", Instant.now(), List.of()));

        ConfigMap cm = client.configMaps().inNamespace("platform").withName("gateway-routes").get();
        assertThat(cm).isNotNull();
        assertThat(cm.getData().get("routes.yaml")).contains("routes:");
    }
}
