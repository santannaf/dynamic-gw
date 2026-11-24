package com.example.gateway.routes.store;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@EnableKubernetesMockClient(crud = true)
class ConfigMapRouteConfigChangeListenerTest {

    private KubernetesClient client;
    private RouteStoreProperties props;

    @BeforeEach
    void setUp() {
        props = new RouteStoreProperties();
        props.getConfigmap().setNamespace("platform");
        props.getConfigmap().setConfigMapName("gateway-routes");
        props.getConfigmap().setKey("routes.yaml");
    }

    @Test
    void onChangeFiresWhenConfigMapIsUpdated() {
        createConfigMap("routes.yaml: |\n  initial");

        AtomicInteger fires = new AtomicInteger();
        ConfigMapRouteConfigChangeListener listener = new ConfigMapRouteConfigChangeListener(client, props);
        try {
            listener.start(fires::incrementAndGet);

            // Update triggers a new resourceVersion and should fire onChange.
            client.configMaps().inNamespace("platform").resource(
                    new ConfigMapBuilder()
                            .withNewMetadata().withName("gateway-routes").withNamespace("platform").endMetadata()
                            .addToData("routes.yaml", "updated")
                            .build()
            ).update();

            await().atMost(5, TimeUnit.SECONDS).untilAtomic(fires, org.hamcrest.Matchers.greaterThanOrEqualTo(1));
        } finally {
            listener.stop();
        }
    }

    @Test
    void onChangeFiresWhenConfigMapIsCreatedAfterStart() {
        AtomicInteger fires = new AtomicInteger();
        ConfigMapRouteConfigChangeListener listener = new ConfigMapRouteConfigChangeListener(client, props);
        try {
            listener.start(fires::incrementAndGet);

            createConfigMap("routes.yaml: created");

            await().atMost(5, TimeUnit.SECONDS).untilAtomic(fires, org.hamcrest.Matchers.greaterThanOrEqualTo(1));
        } finally {
            listener.stop();
        }
    }

    @Test
    void startIsIdempotentAndStopCleanUpInformer() {
        createConfigMap("routes.yaml: x");

        AtomicInteger fires = new AtomicInteger();
        ConfigMapRouteConfigChangeListener listener = new ConfigMapRouteConfigChangeListener(client, props);

        listener.start(fires::incrementAndGet);
        listener.start(fires::incrementAndGet);
        listener.stop();
        listener.stop();

        assertThat(fires)
                .as("idempotent start/stop must not blow up nor fire spurious callbacks")
                .hasValueLessThanOrEqualTo(1);
    }

    @Test
    void stopAfterStartHaltsCallbacks() {
        createConfigMap("routes.yaml: pre");

        AtomicInteger fires = new AtomicInteger();
        ConfigMapRouteConfigChangeListener listener = new ConfigMapRouteConfigChangeListener(client, props);
        listener.start(fires::incrementAndGet);
        listener.stop();

        int firesAtStop = fires.get();

        client.configMaps().inNamespace("platform").resource(
                new ConfigMapBuilder()
                        .withNewMetadata().withName("gateway-routes").withNamespace("platform").endMetadata()
                        .addToData("routes.yaml", "post-stop")
                        .build()
        ).update();

        // No deterministic way to "wait" for a non-event; sleep briefly to detect spurious fires.
        try {
            Thread.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(fires.get())
                .as("changes after stop() must NOT trigger onChange")
                .isEqualTo(firesAtStop);
    }

    private void createConfigMap(String data) {
        client.configMaps().inNamespace("platform").resource(
                new ConfigMapBuilder()
                        .withNewMetadata().withName("gateway-routes").withNamespace("platform").endMetadata()
                        .addToData("routes.yaml", data)
                        .build()
        ).create();
    }
}
