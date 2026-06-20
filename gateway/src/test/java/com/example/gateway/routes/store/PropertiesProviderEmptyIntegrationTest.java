package com.example.gateway.routes.store;

import com.example.gateway.routing.InMemoryDynamicRouteLocator;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Edge case: properties mode with no routes declared at all. The context
 * MUST still start successfully (empty list is valid configuration, not
 * a startup error) and the locator MUST end up with zero routes.
 */
@SpringBootTest(properties = {
        "gateway.routes.store.type=properties"
        // No routes declared. version stays at its default ("static").
})
class PropertiesProviderEmptyIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    RouteConfigProvider provider;

    @Autowired
    InMemoryDynamicRouteLocator routeLocator;

    @Test
    void contextStartsWithoutKubernetesOrS3Clients() {
        assertThat(context.getBeansOfType(KubernetesClient.class)).isEmpty();
        assertThat(context.getBeansOfType(S3Client.class)).isEmpty();
    }

    @Test
    void emptyRouteListIsValidConfiguration() {
        assertThat(provider).isInstanceOf(PropertiesRouteConfigProvider.class);
        assertThat(provider.load().routes()).isEmpty();
        assertThat(provider.load().version()).isEqualTo("static");
        assertThat(routeLocator.size()).isZero();
    }
}
