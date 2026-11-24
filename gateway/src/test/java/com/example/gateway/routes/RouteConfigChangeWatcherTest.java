package com.example.gateway.routes;

import com.example.gateway.routes.store.RouteConfigChangeListener;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RouteConfigChangeWatcherTest {

    @Test
    void smartLifecyclePhaseIsLateEnoughToRunAfterRefreshableBeans() {
        RouteConfigChangeWatcher watcher = new RouteConfigChangeWatcher(
                noopListener(), mock(GatewayRouteReloadService.class));

        assertThat(watcher.getPhase())
                .as("watcher must shut down before the gateway's refresh listeners")
                .isEqualTo(Integer.MAX_VALUE - 1000);
    }

    @Test
    void startWiresListenerCallbackAndIsRunning() {
        AtomicReference<Runnable> captured = new AtomicReference<>();
        RouteConfigChangeListener listener = new RouteConfigChangeListener() {
            @Override
            public void start(Runnable onChange) {
                captured.set(onChange);
            }

            @Override
            public void stop() {
            }
        };
        GatewayRouteReloadService reloadService = mock(GatewayRouteReloadService.class);

        RouteConfigChangeWatcher watcher = new RouteConfigChangeWatcher(listener, reloadService);
        watcher.start();

        assertThat(watcher.isRunning()).isTrue();
        assertThat(captured.get()).isNotNull();

        captured.get().run();
        verify(reloadService).reloadFromStore();
    }

    @Test
    void startIsIdempotent() {
        AtomicInteger starts = new AtomicInteger();
        RouteConfigChangeListener listener = new RouteConfigChangeListener() {
            @Override
            public void start(Runnable onChange) {
                starts.incrementAndGet();
            }

            @Override
            public void stop() {
            }
        };
        RouteConfigChangeWatcher watcher = new RouteConfigChangeWatcher(
                listener, mock(GatewayRouteReloadService.class));

        watcher.start();
        watcher.start();

        assertThat(starts).hasValue(1);
        assertThat(watcher.isRunning()).isTrue();
    }

    @Test
    void stopDelegatesAndClearsRunningFlag() {
        AtomicInteger stops = new AtomicInteger();
        RouteConfigChangeListener listener = new RouteConfigChangeListener() {
            @Override
            public void start(Runnable onChange) {
            }

            @Override
            public void stop() {
                stops.incrementAndGet();
            }
        };
        RouteConfigChangeWatcher watcher = new RouteConfigChangeWatcher(
                listener, mock(GatewayRouteReloadService.class));

        watcher.start();
        watcher.stop();

        assertThat(stops).hasValue(1);
        assertThat(watcher.isRunning()).isFalse();
    }

    @Test
    void stopBeforeStartIsNoOp() {
        AtomicInteger stops = new AtomicInteger();
        RouteConfigChangeListener listener = new RouteConfigChangeListener() {
            @Override
            public void start(Runnable onChange) {
            }

            @Override
            public void stop() {
                stops.incrementAndGet();
            }
        };
        RouteConfigChangeWatcher watcher = new RouteConfigChangeWatcher(
                listener, mock(GatewayRouteReloadService.class));

        watcher.stop();

        assertThat(stops).hasValue(0);
        assertThat(watcher.isRunning()).isFalse();
    }

    @Test
    void reloadCallbackSwallowsExceptions() {
        AtomicReference<Runnable> captured = new AtomicReference<>();
        RouteConfigChangeListener listener = new RouteConfigChangeListener() {
            @Override
            public void start(Runnable onChange) {
                captured.set(onChange);
            }

            @Override
            public void stop() {
            }
        };
        GatewayRouteReloadService reloadService = mock(GatewayRouteReloadService.class);
        when(reloadService.reloadFromStore()).thenThrow(new RuntimeException("provider boom"));

        RouteConfigChangeWatcher watcher = new RouteConfigChangeWatcher(listener, reloadService);
        watcher.start();

        captured.get().run();
        captured.get().run();

        verify(reloadService, times(2)).reloadFromStore();
        assertThat(watcher.isRunning())
                .as("a transient reload failure must NOT take the watcher offline")
                .isTrue();
    }

    private static RouteConfigChangeListener noopListener() {
        return new RouteConfigChangeListener() {
            @Override
            public void start(Runnable onChange) {
            }

            @Override
            public void stop() {
            }
        };
    }
}
