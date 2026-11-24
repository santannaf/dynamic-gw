package com.example.gateway.routes.store;

import com.example.gateway.routing.InMemoryDynamicRouteLocator;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Edge case: properties mode with an empty route list. The context
 * MUST still start successfully (empty list is valid configuration, not
 * a startup error) and the locator MUST end up with zero routes.
 */
@SpringBootTest(properties = {
        "gateway.routes.store.type=properties",
        // Empty whatever application.yaml declares: an empty value replaces the whole list.
        "gateway.routes.store.properties.routes=",
        "gateway.routes.store.properties.version=static"
})
class PropertiesProviderEmptyIntegrationTest {

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
    void emptyRouteListIsValidConfiguration() {
        assertThat(provider).isInstanceOf(PropertiesRouteConfigProvider.class);
        assertThat(provider.load().routes()).isEmpty();
        assertThat(provider.load().version()).isEqualTo("static");
        assertThat(routeLocator.size()).isZero();
    }
}
