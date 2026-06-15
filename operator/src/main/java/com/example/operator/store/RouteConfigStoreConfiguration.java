package com.example.operator.store;

import com.example.shared.routes.SnapshotCodec;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
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
    public SnapshotCodec snapshotCodec() {
        return new SnapshotCodec();
    }

    @Bean(destroyMethod = "close")
    public KubernetesClient kubernetesClient() {
        return new KubernetesClientBuilder().build();
    }

    @Bean(destroyMethod = "close")
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
    public RouteConfigPublisher routeConfigPublisher(RouteStoreProperties properties,
                                                     SnapshotCodec codec,
                                                     KubernetesClient kubernetesClient,
                                                     S3Client s3Client) {
        if ("s3".equalsIgnoreCase(properties.getType())) {
            return new S3RouteConfigPublisher(s3Client, codec, properties);
        }
        return new ConfigMapRouteConfigPublisher(kubernetesClient, codec, properties);
    }
}
