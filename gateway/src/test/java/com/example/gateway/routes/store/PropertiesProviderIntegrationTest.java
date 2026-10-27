package com.example.gateway.routes.store;

import com.example.gateway.routing.InMemoryDynamicRouteLocator;
import com.example.shared.routes.RouteAccess;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration smoke test of the v1 standalone path: the full Spring Boot
 * context comes up with {@code gateway.routes.store.type=properties}, no
 * Kubernetes apiserver, no AWS credentials, and the routes declared inline
 * end up live in the {@link InMemoryDynamicRouteLocator}.
 */
@SpringBootTest(properties = {
        "gateway.routes.store.type=properties",
        "gateway.routes.store.properties.version=integration-test",
        "gateway.routes.store.properties.routes[0].id=httpbin-route",
        "gateway.routes.store.properties.routes[0].path=/httpbin/**",
        "gateway.routes.store.properties.routes[0].target-uri=http://httpbin.example.com:8080",
        "gateway.routes.store.properties.routes[0].strip-prefix=1",
        "gateway.routes.store.properties.routes[0].methods[0]=GET",
        "gateway.routes.store.properties.routes[0].enabled=true",
        "gateway.routes.store.properties.routes[1].id=anything-route",
        "gateway.routes.store.properties.routes[1].path=/anything/**",
        "gateway.routes.store.properties.routes[1].target-uri=http://anything.example.com:8080",
        "gateway.routes.store.properties.routes[1].strip-prefix=0",
        "gateway.routes.store.properties.routes[1].methods[0]=GET",
        "gateway.routes.store.properties.routes[1].enabled=true",
        "gateway.routes.store.properties.routes[1].access.mode=authenticated",
        "gateway.routes.store.properties.routes[1].access.any-of[0]=group:tools-httpbin",
        "gateway.routes.store.properties.routes[1].access.forward-token=true"
})
class PropertiesProviderIntegrationTest {

    @Autowired
    ConfigurableApplicationContext context;

    @Autowired
    RouteConfigProvider provider;

    @Autowired
    InMemoryDynamicRouteLocator routeLocator;

    @Test
    void contextStartsWithoutKubernetesOrS3Clients() {
        assertThat(context.getBeansOfType(KubernetesClient.class)).isEmpty();
        // getBeansOfType would create the lazy S3Client; it must just never be created here.
        assertThat(context.getBeanNamesForType(S3Client.class)).allSatisfy(name ->
                assertThat(context.getBeanFactory().containsSingleton(name)).isFalse());
    }

    @Test
    void activeProviderIsPropertiesBacked() {
        assertThat(provider).isInstanceOf(PropertiesRouteConfigProvider.class);
        assertThat(provider.load().version()).isEqualTo("integration-test");
        assertThat(provider.load().routes()).extracting("id")
                .containsExactly("httpbin-route", "anything-route");
    }

    @Test
    void routesArePresentInLocatorAfterBootstrap() {
        // GatewayRoutesBootstrap runs as an ApplicationRunner on startup; by the
        // time the SpringBootTest context is ready, the locator must have the
        // routes from the properties snapshot in their declared order.
        assertThat(routeLocator.size()).isEqualTo(2);
        assertThat(routeLocator.currentRoutes())
                .extracting(r -> r.getId())
                .containsExactlyInAnyOrder("httpbin-route", "anything-route");
    }

    @Test
    void accessBindsFromKebabCaseAndAbsentStaysNull() {
        assertThat(provider.load().routes().get(0).access()).isNull();
        assertThat(provider.load().routes().get(1).access())
                .isEqualTo(new RouteAccess("authenticated", List.of("group:tools-httpbin"), true));
    }
}
