package com.example.gateway.routes.store;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PropertiesRouteConfigChangeListenerTest {

    @Test
    void startNeverInvokesTheOnChangeCallback() {
        PropertiesRouteConfigChangeListener listener = new PropertiesRouteConfigChangeListener();
        AtomicInteger invocations = new AtomicInteger();

        for (int i = 0; i < 100; i++) {
            listener.start(invocations::incrementAndGet);
        }
        listener.stop();

        assertThat(invocations).hasValue(0);
    }

    @Test
    void stopIsSafeBeforeStart() {
        PropertiesRouteConfigChangeListener listener = new PropertiesRouteConfigChangeListener();
        listener.stop(); // would throw NPE / IllegalStateException if it touched state
        // implicit assertion: no exception
    }
}
