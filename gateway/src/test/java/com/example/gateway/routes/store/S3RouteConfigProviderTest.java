package com.example.gateway.routes.store;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3RouteConfigProviderTest {

    @Test
    void loadThrowsUnsupportedOperationException() {
        S3RouteConfigProvider provider = new S3RouteConfigProvider();

        assertThatThrownBy(provider::load)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("S3 route config store is not implemented in this POC");
    }
}
