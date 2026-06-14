package com.example.operator.store;

import com.example.shared.routes.RouteConfigSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3RouteConfigPublisherTest {

    @Test
    void publishThrowsUnsupportedOperationException() {
        S3RouteConfigPublisher publisher = new S3RouteConfigPublisher();
        RouteConfigSnapshot snapshot = new RouteConfigSnapshot("v1", Instant.now(), List.of());

        assertThatThrownBy(() -> publisher.publish(snapshot))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("S3 route config store is not implemented in this POC");
    }
}
