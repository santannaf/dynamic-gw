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

/**
 * Wiring do backend de snapshot.
 *
 * Por que NÃO usamos {@code @ConditionalOnProperty} para alternar entre
 * ConfigMap e S3: o Spring AOT (processado em build-time do native image)
 * avalia condições com o environment do build (application.yaml apenas), o
 * que congela a decisão e elimina o ramo perdedor do binário. Para suportar
 * troca em runtime via env var em ambos os modos (JVM e native), os dois
 * clients são registrados de forma incondicional e a escolha é feita por
 * {@code if} no bean factory do publisher.
 *
 * Custo de registrar ambos os clients sem usar um: nulo. Tanto Fabric8 quanto
 * AWS SDK v2 só abrem conexão na primeira chamada de API; o {@code build()}
 * em si só monta o cliente em memória.
 */
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
