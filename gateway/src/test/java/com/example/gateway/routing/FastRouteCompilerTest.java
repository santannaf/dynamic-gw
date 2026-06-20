package com.example.gateway.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webflux.autoconfigure.WebFluxProperties;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.StripPrefixGatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.MethodRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteDefinition;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FastRouteCompilerTest {

    private GatewayProperties gatewayProperties;
    private List<RoutePredicateFactory<?>> predicateFactories;
    private List<GatewayFilterFactory<?>> filterFactories;
    private WebFluxProperties webFluxProperties;

    @BeforeEach
    void setUp() {
        gatewayProperties = new GatewayProperties();
        webFluxProperties = new WebFluxProperties();
        predicateFactories = List.of(
                new PathRoutePredicateFactory(webFluxProperties),
                new MethodRoutePredicateFactory());
        filterFactories = List.of(new StripPrefixGatewayFilterFactory());
    }

    @Test
    void simplePathOnlyRouteCompilesOnFastPath() {
        AtomicReference<List<RouteDefinition>> fallbackInput = new AtomicReference<>();
        RouteCompiler fallback = defs -> {
            fallbackInput.set(defs);
            return List.of();
        };
        FastRouteCompiler compiler = new FastRouteCompiler(
                predicateFactories, filterFactories, gatewayProperties, webFluxProperties, fallback);

        List<Route> compiled = compiler.compile(List.of(routeDef("r1", "/a/**")));

        assertThat(compiled).extracting(Route::getId).containsExactly("r1");
        assertThat(fallbackInput.get())
                .as("fast-only batches must skip the fallback")
                .isNull();
    }

    @Test
    void routeWithUnknownPredicateFallsBack() {
        RouteDefinition slow = new RouteDefinition();
        slow.setId("slow");
        slow.setUri(URI.create("http://slow"));
        PredicateDefinition predicate = new PredicateDefinition();
        predicate.setName("Host");
        predicate.addArg("_genkey_0", "example.com");
        slow.setPredicates(List.of(predicate));
        slow.setFilters(List.of());

        AtomicReference<List<RouteDefinition>> fallbackInput = new AtomicReference<>();
        RouteCompiler fallback = defs -> {
            fallbackInput.set(defs);
            return List.of(Route.async(defs.get(0)).asyncPredicate(e -> Mono.just(true)).build());
        };

        FastRouteCompiler compiler = new FastRouteCompiler(
                predicateFactories, filterFactories, gatewayProperties, webFluxProperties, fallback);

        List<Route> compiled = compiler.compile(List.of(slow));

        assertThat(fallbackInput.get()).extracting(RouteDefinition::getId).containsExactly("slow");
        assertThat(compiled).extracting(Route::getId).containsExactly("slow");
    }

    @Test
    void routeWithUnknownFilterFallsBack() {
        RouteDefinition slow = new RouteDefinition();
        slow.setId("slow-filter");
        slow.setUri(URI.create("http://slow"));
        PredicateDefinition path = new PredicateDefinition();
        path.setName("Path");
        path.addArg("_genkey_0", "/a/**");
        FilterDefinition filter = new FilterDefinition();
        filter.setName("AddRequestHeader");
        filter.addArg("_genkey_0", "X-Foo");
        filter.addArg("_genkey_1", "bar");
        slow.setPredicates(List.of(path));
        slow.setFilters(List.of(filter));

        AtomicReference<List<RouteDefinition>> fallbackInput = new AtomicReference<>();
        RouteCompiler fallback = defs -> {
            fallbackInput.set(defs);
            return List.of(Route.async(defs.get(0)).asyncPredicate(e -> Mono.just(true)).build());
        };

        FastRouteCompiler compiler = new FastRouteCompiler(
                predicateFactories, filterFactories, gatewayProperties, webFluxProperties, fallback);

        compiler.compile(List.of(slow));

        assertThat(fallbackInput.get())
                .as("non-StripPrefix filters force fallback")
                .extracting(RouteDefinition::getId).containsExactly("slow-filter");
    }

    @Test
    void defaultFiltersForceAllRoutesThroughFallback() {
        FilterDefinition defaultFilter = new FilterDefinition();
        defaultFilter.setName("AddResponseHeader");
        defaultFilter.addArg("_genkey_0", "X-App");
        defaultFilter.addArg("_genkey_1", "test");
        gatewayProperties.setDefaultFilters(List.of(defaultFilter));

        AtomicReference<List<RouteDefinition>> fallbackInput = new AtomicReference<>();
        RouteCompiler fallback = defs -> {
            fallbackInput.set(defs);
            return List.of();
        };

        FastRouteCompiler compiler = new FastRouteCompiler(
                predicateFactories, filterFactories, gatewayProperties, webFluxProperties, fallback);

        compiler.compile(List.of(routeDef("a", "/a/**"), routeDef("b", "/b/**")));

        assertThat(fallbackInput.get())
                .as("any defaultFilters configuration disables the fast path entirely")
                .extracting(RouteDefinition::getId).containsExactly("a", "b");
    }

    @Test
    void mixedBatchSplitsBetweenFastAndFallback() {
        RouteDefinition fast = routeDef("fast", "/fast/**");
        RouteDefinition slow = new RouteDefinition();
        slow.setId("slow");
        slow.setUri(URI.create("http://slow"));
        PredicateDefinition host = new PredicateDefinition();
        host.setName("Host");
        host.addArg("_genkey_0", "example.com");
        slow.setPredicates(List.of(host));
        slow.setFilters(List.of());

        AtomicReference<List<RouteDefinition>> fallbackInput = new AtomicReference<>();
        RouteCompiler fallback = defs -> {
            fallbackInput.set(defs);
            return defs.stream()
                    .map(rd -> Route.async(rd).asyncPredicate(e -> Mono.just(true)).build())
                    .toList();
        };

        FastRouteCompiler compiler = new FastRouteCompiler(
                predicateFactories, filterFactories, gatewayProperties, webFluxProperties, fallback);

        List<Route> compiled = compiler.compile(List.of(fast, slow));

        assertThat(fallbackInput.get()).extracting(RouteDefinition::getId).containsExactly("slow");
        assertThat(compiled).extracting(Route::getId).containsExactlyInAnyOrder("fast", "slow");
    }

    @Test
    void constructorFailsWhenPathFactoryMissing() {
        List<RoutePredicateFactory<?>> noPath = List.of(new MethodRoutePredicateFactory());

        assertThatThrownBy(() -> new FastRouteCompiler(
                noPath, filterFactories, gatewayProperties, webFluxProperties, defs -> List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PathRoutePredicateFactory");
    }

    @Test
    void constructorFailsWhenMethodFactoryMissing() {
        List<RoutePredicateFactory<?>> noMethod = List.of(new PathRoutePredicateFactory(webFluxProperties));

        assertThatThrownBy(() -> new FastRouteCompiler(
                noMethod, filterFactories, gatewayProperties, webFluxProperties, defs -> List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MethodRoutePredicateFactory");
    }

    @Test
    void constructorFailsWhenStripPrefixFactoryMissing() {
        List<GatewayFilterFactory<?>> noStrip = List.of();

        assertThatThrownBy(() -> new FastRouteCompiler(
                predicateFactories, noStrip, gatewayProperties, webFluxProperties, defs -> List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("StripPrefixGatewayFilterFactory");
    }

    @Test
    void emptyDefinitionsReturnsEmptyList() {
        FastRouteCompiler compiler = new FastRouteCompiler(
                predicateFactories, filterFactories, gatewayProperties, webFluxProperties, defs -> List.of());

        assertThat(compiler.compile(List.of())).isEmpty();
    }

    private static RouteDefinition routeDef(String id, String path) {
        RouteDefinition rd = new RouteDefinition();
        rd.setId(id);
        rd.setUri(URI.create("http://upstream"));
        PredicateDefinition predicate = new PredicateDefinition();
        predicate.setName("Path");
        predicate.addArg("_genkey_0", path);
        rd.setPredicates(List.of(predicate));
        FilterDefinition filter = new FilterDefinition();
        filter.setName("StripPrefix");
        filter.addArg("_genkey_0", "1");
        rd.setFilters(List.of(filter));
        return rd;
    }
}
