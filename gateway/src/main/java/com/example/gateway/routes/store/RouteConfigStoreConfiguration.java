package com.example.gateway.routes.store;

import com.example.shared.routes.SnapshotCodec;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RouteStoreProperties.class)
public class RouteConfigStoreConfiguration {

    @Bean
    public SnapshotCodec snapshotCodec() {
        return new SnapshotCodec();
    }

    @Configuration
    @ConditionalOnProperty(name = "gateway.routes.store.type", havingValue = "configmap", matchIfMissing = true)
    static class ConfigMapStore {

        @Bean(destroyMethod = "close")
        public KubernetesClient kubernetesClient() {
            return new KubernetesClientBuilder().build();
        }

        @Bean
        public RouteConfigProvider routeConfigProvider(KubernetesClient client,
                                                       SnapshotCodec codec,
                                                       RouteStoreProperties properties) {
            return new ConfigMapRouteConfigProvider(client, codec, properties);
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "gateway.routes.store.type", havingValue = "s3")
    static class S3Store {

        @Bean
        public RouteConfigProvider routeConfigProvider() {
            return new S3RouteConfigProvider();
        }
    }
}
