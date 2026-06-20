package com.example.operator.reconcile;

import com.example.operator.crd.GatewayRoute;
import com.example.operator.store.RouteConfigPublisher;
import com.example.shared.routes.RouteConfigSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class GatewayRouteReconciler {

    private static final Logger log = LoggerFactory.getLogger(GatewayRouteReconciler.class);

    private final SnapshotBuilder snapshotBuilder;
    private final RouteConfigPublisher publisher;
    private final Clock clock;
    private final AtomicLong reconcileCounter = new AtomicLong();

    public GatewayRouteReconciler(SnapshotBuilder snapshotBuilder,
                                  RouteConfigPublisher publisher,
                                  Clock clock) {
        this.snapshotBuilder = snapshotBuilder;
        this.publisher = publisher;
        this.clock = clock;
    }

    public void reconcile(List<GatewayRoute> currentRoutes) {
        long id = reconcileCounter.incrementAndGet();
        log.info("Reconcile started id={} routes={}", id, currentRoutes.size());

        SnapshotBuilder.Result result = snapshotBuilder.build(currentRoutes, clock);
        log.info("Reconcile id={} listed {} GatewayRoutes ({} valid, {} disabled, {} invalid)",
                id, result.total(), result.valid(), result.disabled(), result.invalid());

        RouteConfigSnapshot snapshot = result.snapshot();
        log.info("Reconcile id={} snapshot built version={} entries={}",
                id, snapshot.version(), snapshot.routes().size());

        try {
            publisher.publish(snapshot);
        } catch (RuntimeException e) {
            log.error("Reconcile id={} publish failed: {}", id, e.getMessage(), e);
            return;
        }
        log.info("Reconcile id={} snapshot published to backend version={}",
                id, snapshot.version());
    }
}
