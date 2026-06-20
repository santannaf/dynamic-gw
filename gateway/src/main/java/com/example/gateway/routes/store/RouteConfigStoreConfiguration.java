package com.example.gateway.routes.store;

import com.example.shared.routes.SnapshotCodec;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

@Configuration
@EnableConfigurationProperties(RouteStoreProperties.class)
public class RouteConfigStoreConfiguration {

    @Bean
    public SnapshotCodec snapshotCodec() { return new SnapshotCodec(); }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "gateway.routes.store.type", havingValue = "configmap", matchIfMissing = true)
    public KubernetesClient kubernetesClient() { return new KubernetesClientBuilder().build(); }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "gateway.routes.store.type", havingValue = "s3")
    public S3Client s3Client(RouteStoreProperties properties) {
        RouteStoreProperties.S3 s3 = properties.getS3();
        S3ClientBuilder builder = S3Client.builder()
                .httpClient(UrlConnectionHttpClient.create())
                .region(Region.of(s3.getRegion()));
        if (s3.getEndpoint() != null && !s3.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(s3.getEndpoint()));
        }
        if (s3.isPathStyleAccess()) {
            builder.serviceConfiguration(S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .build());
        }
        return builder.build();
    }

    @Bean
    public RouteConfigProvider routeConfigProvider(RouteStoreProperties properties,
                                                   SnapshotCodec codec,
                                                   ObjectProvider<KubernetesClient> kubernetesClient,
                                                   ObjectProvider<S3Client> s3Client) {
        String type = properties.getType();
        if ("properties".equalsIgnoreCase(type)) {
            return new PropertiesRouteConfigProvider(properties);
        }
        if ("s3".equalsIgnoreCase(type)) {
            return new S3RouteConfigProvider(s3Client.getObject(), codec, properties);
        }
        return new ConfigMapRouteConfigProvider(kubernetesClient.getObject(), codec, properties);
    }

    @Bean
    public RouteConfigChangeListener routeConfigChangeListener(RouteStoreProperties properties,
                                                               ObjectProvider<KubernetesClient> kubernetesClient,
                                                               ObjectProvider<S3Client> s3Client) {
        String type = properties.getType();
        if ("properties".equalsIgnoreCase(type)) {
            return new PropertiesRouteConfigChangeListener();
        }
        if ("s3".equalsIgnoreCase(type)) {
            return new S3RouteConfigChangeListener(s3Client.getObject(), properties);
        }
        return new ConfigMapRouteConfigChangeListener(kubernetesClient.getObject(), properties);
    }
}