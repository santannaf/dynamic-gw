package com.example.operator.reconcile;

import com.example.operator.crd.GatewayRoute;
import com.example.operator.signal.GatewayReloadSignaler;
import com.example.operator.store.RouteConfigPublisher;
import com.example.shared.routes.RouteConfigSnapshot;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.example.operator.reconcile.GatewayRouteValidatorTest.route;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class GatewayRouteReconcilerTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-13T10:00:00Z"), ZoneOffset.UTC);
    private final SnapshotBuilder builder = new SnapshotBuilder(new GatewayRouteValidator());

    @Test
    void happyPathPublishesThenSignals() {
        RouteConfigPublisher publisher = mock(RouteConfigPublisher.class);
        GatewayReloadSignaler signaler = mock(GatewayReloadSignaler.class);
        GatewayRouteReconciler reconciler = new GatewayRouteReconciler(builder, publisher, signaler, clock);

        reconciler.reconcile(List.of(
                route("a", "/a/**", "http://a", 1, null, true),
                route("b", "/b/**", "http://b", 1, null, true)
        ));

        ArgumentCaptor<RouteConfigSnapshot> captor = ArgumentCaptor.forClass(RouteConfigSnapshot.class);
        verify(publisher, times(1)).publish(captor.capture());
        assertThat(captor.getValue().routes()).extracting(e -> e.id()).containsExactly("a", "b");
        verify(signaler, times(1)).signal();
    }

    @Test
    void publisherFailureSkipsSignal() {
        RouteConfigPublisher publisher = mock(RouteConfigPublisher.class);
        doThrow(new RuntimeException("backend down")).when(publisher).publish(org.mockito.ArgumentMatchers.any());
        GatewayReloadSignaler signaler = mock(GatewayReloadSignaler.class);
        GatewayRouteReconciler reconciler = new GatewayRouteReconciler(builder, publisher, signaler, clock);

        reconciler.reconcile(List.of(route("a", "/a/**", "http://a", 1, null, true)));

        verify(publisher).publish(org.mockito.ArgumentMatchers.any());
        verify(signaler, never()).signal();
    }

    @Test
    void emptyInputStillSignalsToClearGatewayRoutes() {
        RouteConfigPublisher publisher = mock(RouteConfigPublisher.class);
        GatewayReloadSignaler signaler = mock(GatewayReloadSignaler.class);
        GatewayRouteReconciler reconciler = new GatewayRouteReconciler(builder, publisher, signaler, clock);

        reconciler.reconcile(List.of());

        verify(publisher, times(1)).publish(org.mockito.ArgumentMatchers.any());
        verify(signaler, times(1)).signal();
    }
}
