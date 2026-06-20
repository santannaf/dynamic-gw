package com.example.gateway.routes.store;

import com.example.shared.routes.RouteConfigEntry;
import com.example.shared.routes.RouteConfigSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PropertiesRouteConfigProviderTest {

    @Test
    void singleRouteIsBoundIntoTheSnapshot() {
        RouteStoreProperties props = new RouteStoreProperties();
        props.setType("properties");
        RouteStoreProperties.Properties cfg = props.getProperties();
        cfg.setVersion("v1.0.0");
        cfg.setRoutes(List.of(
                new RouteConfigEntry("httpbin-route", "/httpbin/**",
                        "http://httpbin.example.com:8080", 1, List.of("GET", "POST"), true,
                        "platform-team", "Echoes the request via httpbin for smoke testing.")
        ));

        PropertiesRouteConfigProvider provider = new PropertiesRouteConfigProvider(props);
        RouteConfigSnapshot snapshot = provider.load();

        assertThat(snapshot.version()).isEqualTo("v1.0.0");
        assertThat(snapshot.routes()).hasSize(1);
        assertThat(snapshot.routes().get(0).id()).isEqualTo("httpbin-route");
        assertThat(snapshot.routes().get(0).path()).isEqualTo("/httpbin/**");
        assertThat(snapshot.routes().get(0).methods()).containsExactly("GET", "POST");
        assertThat(snapshot.routes().get(0).team()).isEqualTo("platform-team");
        assertThat(snapshot.routes().get(0).description())
                .isEqualTo("Echoes the request via httpbin for smoke testing.");
    }

    @Test
    void emptyRoutesListProducesEmptySnapshot() {
        RouteStoreProperties props = new RouteStoreProperties();
        props.setType("properties");
        // version + routes left at defaults

        PropertiesRouteConfigProvider provider = new PropertiesRouteConfigProvider(props);
        RouteConfigSnapshot snapshot = provider.load();

        assertThat(snapshot.version()).isEqualTo("static");
        assertThat(snapshot.routes()).isEmpty();
    }

    @Test
    void nullRoutesListProducesEmptySnapshotAndDoesNotThrow() {
        RouteStoreProperties props = new RouteStoreProperties();
        props.setType("properties");
        props.getProperties().setRoutes(null);

        PropertiesRouteConfigProvider provider = new PropertiesRouteConfigProvider(props);
        RouteConfigSnapshot snapshot = provider.load();

        assertThat(snapshot.routes()).isEmpty();
    }

    @Test
    void blankVersionFallsBackToStatic() {
        RouteStoreProperties props = new RouteStoreProperties();
        props.getProperties().setVersion("   ");

        PropertiesRouteConfigProvider provider = new PropertiesRouteConfigProvider(props);
        assertThat(provider.load().version()).isEqualTo("static");
    }

    @Test
    void successiveLoadsReturnTheSameSnapshotInstance() {
        RouteStoreProperties props = new RouteStoreProperties();
        props.getProperties().setRoutes(List.of(
                new RouteConfigEntry("a", "/a/**", "http://a", 1, List.of("GET"), true, null, null)
        ));

        PropertiesRouteConfigProvider provider = new PropertiesRouteConfigProvider(props);
        RouteConfigSnapshot first = provider.load();
        RouteConfigSnapshot second = provider.load();

        assertThat(second).isSameAs(first);
    }
}
