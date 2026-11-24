package com.example.operator.watch;

import com.example.operator.crd.GatewayRoute;
import com.example.operator.crd.GatewayRouteList;
import com.example.operator.reconcile.GatewayRouteReconciler;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class GatewayRouteInformerRunner implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GatewayRouteInformerRunner.class);

    private final KubernetesClient client;
    private final GatewayRouteReconciler reconciler;
    private final String namespace;
    private final Duration debounce;

    private volatile boolean running = false;
    private SharedIndexInformer<GatewayRoute> informer;
    private ScheduledExecutorService scheduler;
    private final ReentrantLock scheduleLock = new ReentrantLock();
    private ScheduledFuture<?> pendingReconcile;

    public GatewayRouteInformerRunner(KubernetesClient client,
                                      GatewayRouteReconciler reconciler,
                                      String namespace,
                                      Duration debounce) {
        this.client = client;
        this.reconciler = reconciler;
        this.namespace = namespace;
        this.debounce = debounce;
    }

    @Override
    public synchronized void start() {
        if (running) return;

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gw-reconcile");
            t.setDaemon(false);
            return t;
        });

        informer = client.resources(GatewayRoute.class, GatewayRouteList.class)
                .inNamespace(namespace)
                .inform(new ResourceEventHandler<>() {
                    @Override
                    public void onAdd(GatewayRoute resource) {
                        log.info("GatewayRoute added name={}", nameOf(resource));
                        scheduleReconcile();
                    }

                    @Override
                    public void onUpdate(GatewayRoute oldResource, GatewayRoute newResource) {
                        log.info("GatewayRoute updated name={}", nameOf(newResource));
                        scheduleReconcile();
                    }

                    @Override
                    public void onDelete(GatewayRoute resource, boolean deletedFinalStateUnknown) {
                        log.info("GatewayRoute deleted name={}", nameOf(resource));
                        scheduleReconcile();
                    }
                });
        running = true;
        log.info("GatewayRoute informer started namespace={}", namespace);
        scheduleReconcile();
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (informer != null) {
            try {
                informer.close();
            } catch (Exception e) {
                log.warn("Failed to close informer: {}", e.getMessage());
            }
            informer = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        log.info("GatewayRoute informer stopped");
    }

    @Override
    public boolean isRunning() { return running; }

    void scheduleReconcile() {
        scheduleLock.lock();
        try {
            if (pendingReconcile != null && !pendingReconcile.isDone()) {
                pendingReconcile.cancel(false);
            }
            if (scheduler == null) {
                return;
            }
            pendingReconcile = scheduler.schedule(this::runReconcile,
                    debounce.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            scheduleLock.unlock();
        }
    }

    void runReconcile() {
        try {
            List<GatewayRoute> snapshot;
            if (informer != null) {
                snapshot = List.copyOf(informer.getIndexer().list());
            } else {
                snapshot = client.resources(GatewayRoute.class, GatewayRouteList.class)
                        .inNamespace(namespace)
                        .list()
                        .getItems();
            }
            reconciler.reconcile(snapshot);
        } catch (Exception e) {
            log.error("Reconcile pass failed: {}", e.getMessage(), e);
        }
    }

    private static String nameOf(GatewayRoute r) {
        return r != null && r.getMetadata() != null ? r.getMetadata().getName() : "<unknown>";
    }
}
