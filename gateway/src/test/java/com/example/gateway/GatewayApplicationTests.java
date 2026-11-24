package com.example.gateway;

import com.example.gateway.routes.store.RouteConfigChangeListener;
import com.example.gateway.routes.store.RouteConfigProvider;
import com.example.shared.routes.RouteConfigSnapshot;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.services.s3.S3Client;

@SpringBootTest
class GatewayApplicationTests {

    @Test
    void contextLoads() {
    }

    @TestConfiguration
    static class TestBeans {

        @Bean
        @Primary
        KubernetesClient testKubernetesClient() {
            return Mockito.mock(KubernetesClient.class, Mockito.RETURNS_DEEP_STUBS);
        }

        @Bean
        @Primary
        S3Client testS3Client() {
            return Mockito.mock(S3Client.class);
        }

        @Bean
        @Primary
        RouteConfigProvider testRouteConfigProvider() {
            return RouteConfigSnapshot::empty;
        }

        @Bean
        @Primary
        RouteConfigChangeListener testRouteConfigChangeListener() {
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
}
