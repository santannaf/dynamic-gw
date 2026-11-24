package com.example.operator;

import com.example.operator.reconcile.GatewayRouteReconciler;
import com.example.operator.store.RouteConfigPublisher;
import com.example.operator.watch.GatewayRouteInformerRunner;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Duration;

@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "spring.main.allow-bean-definition-overriding=true"
})
class OperatorApplicationTests {

    @Test
    void contextLoads() {
    }

    @TestConfiguration
    static class TestBeans {

        @Bean
        @Primary
        KubernetesClient kubernetesClient() {
            return Mockito.mock(KubernetesClient.class);
        }

        @Bean
        @Primary
        S3Client s3Client() {
            return Mockito.mock(S3Client.class);
        }

        @Bean
        @Primary
        RouteConfigPublisher routeConfigPublisher() {
            return snapshot -> { /* no-op */ };
        }

        /**
         * Replaces the production runner by matching its bean name. Without this, both
         * SmartLifecycle beans would be started and the real one would NPE on the mocked
         * KubernetesClient. Requires spring.main.allow-bean-definition-overriding=true.
         */
        @Bean("gatewayRouteInformerRunner")
        GatewayRouteInformerRunner gatewayRouteInformerRunner(KubernetesClient client,
                                                              GatewayRouteReconciler reconciler) {
            return new GatewayRouteInformerRunner(client, reconciler, "platform", Duration.ofMillis(100)) {
                @Override
                public void start() {
                }

                @Override
                public void stop() {
                }

                @Override
                public boolean isRunning() {
                    return false;
                }
            };
        }
    }
}
