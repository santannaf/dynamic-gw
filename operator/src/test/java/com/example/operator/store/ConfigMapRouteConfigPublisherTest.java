package com.example.operator.store;

import com.example.shared.routes.RouteConfigEntry;
import com.example.shared.routes.RouteConfigSnapshot;
import com.example.shared.routes.SnapshotCodec;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnableKubernetesMockClient(crud = true)
class ConfigMapRouteConfigPublisherTest {

    private KubernetesClient client;

    @Test
    void publishCreatesGzippedConfigMapByDefault() {
        RouteStoreProperties properties = new RouteStoreProperties();
        SnapshotCodec codec = new SnapshotCodec();
        ConfigMapRouteConfigPublisher publisher = new ConfigMapRouteConfigPublisher(client, codec, properties);

        RouteConfigSnapshot snapshot = new RouteConfigSnapshot(
                "v1",
                Instant.parse("2026-06-13T10:00:00Z"),
                List.of(new RouteConfigEntry("httpbin-route", "/httpbin/**", "http://httpbin.org", 1, List.of("GET"), true, null, null))
        );

        publisher.publish(snapshot);

        ConfigMap cm = client.configMaps().inNamespace("platform").withName("gateway-routes").get();
        assertThat(cm).isNotNull();
        // gzip is on by default, so payload lives under binaryData[<key>.gz], base64-encoded.
        assertThat(cm.getData())
                .as("plain-text key must NOT be populated when gzip is enabled")
                .doesNotContainKey("routes.yaml");
        String b64 = cm.getBinaryData().get("routes.yaml.gz");
        assertThat(b64).isNotBlank();
        RouteConfigSnapshot roundTrip = codec.readYamlGz(Base64.getDecoder().decode(b64));
        assertThat(roundTrip.routes())
                .extracting(RouteConfigEntry::id)
                .containsExactly("httpbin-route");
        assertThat(roundTrip.routes().get(0).targetUri()).isEqualTo("http://httpbin.org");
    }

    @Test
    void publishUpdatesExistingConfigMap() {
        RouteStoreProperties properties = new RouteStoreProperties();
        SnapshotCodec codec = new SnapshotCodec();
        ConfigMapRouteConfigPublisher publisher = new ConfigMapRouteConfigPublisher(client, codec, properties);

        publisher.publish(new RouteConfigSnapshot(
                "v1", Instant.now(),
                List.of(new RouteConfigEntry("first", "/first/**", "http://first", 1, null, true, null, null))
        ));
        publisher.publish(new RouteConfigSnapshot(
                "v2", Instant.now(),
                List.of(new RouteConfigEntry("second", "/second/**", "http://second", 1, null, true, null, null))
        ));

        ConfigMap cm = client.configMaps().inNamespace("platform").withName("gateway-routes").get();
        assertThat(cm.getMetadata().getNamespace()).isEqualTo("platform");
        assertThat(cm.getMetadata().getName()).isEqualTo("gateway-routes");
        String b64 = cm.getBinaryData().get("routes.yaml.gz");
        RouteConfigSnapshot latest = codec.readYamlGz(Base64.getDecoder().decode(b64));
        assertThat(latest.routes()).extracting(RouteConfigEntry::id).containsExactly("second");
    }

    @Test
    void publishWritesPlainYamlWhenGzipIsDisabled() {
        RouteStoreProperties properties = new RouteStoreProperties();
        properties.getConfigmap().setGzip(false);
        SnapshotCodec codec = new SnapshotCodec();
        ConfigMapRouteConfigPublisher publisher = new ConfigMapRouteConfigPublisher(client, codec, properties);

        publisher.publish(new RouteConfigSnapshot(
                "v1", Instant.now(),
                List.of(new RouteConfigEntry("plain", "/plain/**", "http://plain", 1, null, true, null, null))
        ));

        ConfigMap cm = client.configMaps().inNamespace("platform").withName("gateway-routes").get();
        assertThat(cm).isNotNull();
        assertThat(cm.getData().get("routes.yaml"))
                .contains("plain")
                .contains("http://plain");
        assertThat(cm.getBinaryData())
                .as("binaryData must NOT be populated when gzip is disabled")
                .doesNotContainKey("routes.yaml.gz");
    }

    @Test
    void emptySnapshotIsPublishedWithoutFailure() {
        RouteStoreProperties properties = new RouteStoreProperties();
        SnapshotCodec codec = new SnapshotCodec();
        ConfigMapRouteConfigPublisher publisher = new ConfigMapRouteConfigPublisher(client, codec, properties);

        publisher.publish(new RouteConfigSnapshot("empty", Instant.now(), List.of()));

        ConfigMap cm = client.configMaps().inNamespace("platform").withName("gateway-routes").get();
        assertThat(cm).isNotNull();
        String b64 = cm.getBinaryData().get("routes.yaml.gz");
        assertThat(b64).isNotBlank();
        RouteConfigSnapshot roundTrip = codec.readYamlGz(Base64.getDecoder().decode(b64));
        assertThat(roundTrip.routes()).isEmpty();
    }
}
