package com.example.operator.reconcile;

import com.example.operator.store.RouteConfigPublisher;
import com.example.shared.routes.RouteConfigSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.example.operator.reconcile.GatewayRouteValidatorTest.route;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class GatewayRouteReconcilerTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-13T10:00:00Z"), ZoneOffset.UTC);
    private final SnapshotBuilder builder = new SnapshotBuilder(new GatewayRouteValidator());

    @Test
    void happyPathPublishesSnapshotInIdOrder() {
        RouteConfigPublisher publisher = mock(RouteConfigPublisher.class);
        GatewayRouteReconciler reconciler = new GatewayRouteReconciler(builder, publisher, clock);

        reconciler.reconcile(List.of(
                route("a", "/a/**", "http://a", 1, null, true),
                route("b", "/b/**", "http://b", 1, null, true)
        ));

        ArgumentCaptor<RouteConfigSnapshot> captor = ArgumentCaptor.forClass(RouteConfigSnapshot.class);
        verify(publisher, times(1)).publish(captor.capture());
        assertThat(captor.getValue().routes()).extracting(e -> e.id()).containsExactly("a", "b");
    }

    @Test
    void publisherFailureIsSwallowedAndDoesNotPropagate() {
        RouteConfigPublisher publisher = mock(RouteConfigPublisher.class);
        doThrow(new RuntimeException("backend down")).when(publisher).publish(any());
        GatewayRouteReconciler reconciler = new GatewayRouteReconciler(builder, publisher, clock);

        reconciler.reconcile(List.of(route("a", "/a/**", "http://a", 1, null, true)));

        verify(publisher).publish(any());
    }

    @Test
    void emptyInputStillPublishesEmptySnapshot() {
        RouteConfigPublisher publisher = mock(RouteConfigPublisher.class);
        GatewayRouteReconciler reconciler = new GatewayRouteReconciler(builder, publisher, clock);

        reconciler.reconcile(List.of());

        ArgumentCaptor<RouteConfigSnapshot> captor = ArgumentCaptor.forClass(RouteConfigSnapshot.class);
        verify(publisher, times(1)).publish(captor.capture());
        assertThat(captor.getValue().routes()).isEmpty();
    }

    @Test
    void invalidEntriesAreDroppedBeforePublish() {
        RouteConfigPublisher publisher = mock(RouteConfigPublisher.class);
        GatewayRouteReconciler reconciler = new GatewayRouteReconciler(builder, publisher, clock);

        reconciler.reconcile(List.of(
                route("ok", "/ok/**", "http://ok", 1, null, true),
                route("bad", "/x/**", "", 1, null, true)
        ));

        ArgumentCaptor<RouteConfigSnapshot> captor = ArgumentCaptor.forClass(RouteConfigSnapshot.class);
        verify(publisher).publish(captor.capture());
        assertThat(captor.getValue().routes()).extracting(e -> e.id()).containsExactly("ok");
    }

    @Test
    void disabledEntriesAreDroppedBeforePublish() {
        RouteConfigPublisher publisher = mock(RouteConfigPublisher.class);
        GatewayRouteReconciler reconciler = new GatewayRouteReconciler(builder, publisher, clock);

        reconciler.reconcile(List.of(
                route("ok", "/ok/**", "http://ok", 1, null, true),
                route("off", "/off/**", "http://off", 1, null, false)
        ));

        ArgumentCaptor<RouteConfigSnapshot> captor = ArgumentCaptor.forClass(RouteConfigSnapshot.class);
        verify(publisher).publish(captor.capture());
        assertThat(captor.getValue().routes()).extracting(e -> e.id()).containsExactly("ok");
    }
}
