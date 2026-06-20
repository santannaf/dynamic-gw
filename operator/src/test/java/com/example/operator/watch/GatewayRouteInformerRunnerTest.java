package com.example.operator.watch;

import com.example.operator.crd.GatewayRoute;
import com.example.operator.crd.GatewayRouteSpec;
import com.example.operator.reconcile.GatewayRouteReconciler;
import com.example.operator.reconcile.GatewayRouteValidator;
import com.example.operator.reconcile.SnapshotBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@EnableKubernetesMockClient(crud = true)
class GatewayRouteInformerRunnerTest {

    static KubernetesMockServer mockServer;
    KubernetesClient client;

    @Test
    void multipleQuickEventsCoalesceIntoOneReconcile() throws Exception {
        AtomicInteger reconcileCount = new AtomicInteger();
        AtomicReference<List<GatewayRoute>> lastList = new AtomicReference<>(List.of());

        SnapshotBuilder builder = new SnapshotBuilder(new GatewayRouteValidator());

        GatewayRouteReconciler reconciler = new GatewayRouteReconciler(
                builder, snapshot -> {}, Clock.systemUTC()) {
            @Override
            public void reconcile(List<GatewayRoute> currentRoutes) {
                lastList.set(currentRoutes);
                reconcileCount.incrementAndGet();
            }
        };

        GatewayRouteInformerRunner runner = new GatewayRouteInformerRunner(
                client, reconciler, "platform", Duration.ofMillis(300));
        try {
            runner.start();
            createRoute("a", "/a/**", "http://a");
            createRoute("b", "/b/**", "http://b");
            createRoute("c", "/c/**", "http://c");

            await().atMost(5, TimeUnit.SECONDS).until(() ->
                    lastList.get().size() >= 3);
            int countAfterFlurry = reconcileCount.get();
            Thread.sleep(600);
            int countAfterIdle = reconcileCount.get();

            assertThat(countAfterIdle)
                    .as("no extra reconciles should fire while the namespace is idle")
                    .isEqualTo(countAfterFlurry);
            assertThat(lastList.get()).extracting(r -> r.getMetadata().getName())
                    .containsExactlyInAnyOrder("a", "b", "c");
            assertThat(countAfterFlurry)
                    .as("the burst of 3 creates should coalesce into far fewer reconciles than 3")
                    .isLessThan(3);
        } finally {
            runner.stop();
        }
    }

    @Test
    void deletedRouteIsAbsentFromNextSnapshot() throws Exception {
        AtomicReference<List<GatewayRoute>> lastList = new AtomicReference<>(List.of());
        SnapshotBuilder builder = new SnapshotBuilder(new GatewayRouteValidator());

        GatewayRouteReconciler reconciler = new GatewayRouteReconciler(
                builder, snapshot -> {}, Clock.systemUTC()) {
            @Override
            public void reconcile(List<GatewayRoute> currentRoutes) {
                lastList.set(currentRoutes);
            }
        };

        GatewayRouteInformerRunner runner = new GatewayRouteInformerRunner(
                client, reconciler, "platform", Duration.ofMillis(100));
        try {
            createRoute("first", "/first/**", "http://first");
            createRoute("second", "/second/**", "http://second");
            runner.start();

            await().atMost(5, TimeUnit.SECONDS).until(() ->
                    lastList.get().stream().anyMatch(r -> "first".equals(r.getMetadata().getName())));

            client.resources(GatewayRoute.class)
                    .inNamespace("platform")
                    .withName("first")
                    .delete();

            await().atMost(5, TimeUnit.SECONDS).until(() ->
                    lastList.get().stream().noneMatch(r -> "first".equals(r.getMetadata().getName())));

            assertThat(lastList.get()).extracting(r -> r.getMetadata().getName())
                    .containsExactly("second");
        } finally {
            runner.stop();
        }
    }

    private void createRoute(String name, String path, String uri) {
        GatewayRoute r = new GatewayRoute();
        r.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace("platform").build());
        GatewayRouteSpec spec = new GatewayRouteSpec();
        spec.setPath(path);
        spec.setTargetUri(uri);
        spec.setStripPrefix(1);
        spec.setEnabled(true);
        r.setSpec(spec);
        client.resources(GatewayRoute.class).inNamespace("platform").resource(r).create();
    }
}
