package com.example.operator;

import com.example.operator.reconcile.GatewayRouteReconciler;
import com.example.operator.reconcile.GatewayRouteValidator;
import com.example.operator.reconcile.SnapshotBuilder;
import com.example.operator.signal.GatewayReloadSignaler;
import com.example.operator.store.RouteConfigPublisher;
import com.example.operator.watch.GatewayRouteInformerRunner;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(OperatorProperties.class)
public class OperatorConfiguration {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    public GatewayRouteValidator gatewayRouteValidator() {
        return new GatewayRouteValidator();
    }

    @Bean
    public SnapshotBuilder snapshotBuilder(GatewayRouteValidator validator) {
        return new SnapshotBuilder(validator);
    }

    @Bean
    public GatewayReloadSignaler gatewayReloadSignaler(OperatorProperties properties) {
        return new GatewayReloadSignaler(properties.getGateway().getReloadUrl());
    }

    @Bean
    public GatewayRouteReconciler gatewayRouteReconciler(SnapshotBuilder builder,
                                                          RouteConfigPublisher publisher,
                                                          GatewayReloadSignaler signaler,
                                                          Clock clock) {
        return new GatewayRouteReconciler(builder, publisher, signaler, clock);
    }

    @Bean
    public GatewayRouteInformerRunner gatewayRouteInformerRunner(KubernetesClient client,
                                                                  GatewayRouteReconciler reconciler,
                                                                  OperatorProperties properties) {
        return new GatewayRouteInformerRunner(
                client,
                reconciler,
                properties.getNamespace(),
                properties.getReconcile().getDebounce()
        );
    }
}
