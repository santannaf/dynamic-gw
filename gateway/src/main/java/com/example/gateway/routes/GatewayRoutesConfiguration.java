package com.example.gateway.routes;

import com.example.gateway.routes.store.RouteConfigChangeListener;
import com.example.gateway.routing.FastRouteCompiler;
import com.example.gateway.routing.RouteCompiler;
import org.springframework.boot.webflux.autoconfigure.WebFluxProperties;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteDefinitionRouteLocator;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Configuration
public class GatewayRoutesConfiguration {

    @Bean
    public RouteConfigChangeWatcher routeConfigChangeWatcher(RouteConfigChangeListener listener,
                                                             GatewayRouteReloadService reloadService) {
        return new RouteConfigChangeWatcher(listener, reloadService);
    }

    @Bean
    public RouteCompiler slowRouteCompiler(List<RoutePredicateFactory<?>> predicateFactories,
                                           List<GatewayFilterFactory<?>> gatewayFilterFactories,
                                           GatewayProperties gatewayProperties,
                                           ConfigurationService configurationService) {
        AtomicReference<List<RouteDefinition>> input = new AtomicReference<>(List.of());
        RouteDefinitionLocator locator = () -> Flux.fromIterable(input.get());
        //noinspection unchecked, rawtypes
        RouteDefinitionRouteLocator internal = new RouteDefinitionRouteLocator(
                locator,
                (List) predicateFactories,
                (List) gatewayFilterFactories,
                gatewayProperties,
                configurationService);
        return definitions -> {
            input.set(definitions);
            List<Route> result = internal.getRoutes().collectList().block();
            return result == null ? List.of() : result;
        };
    }

    @Bean
    @Primary
    public RouteCompiler routeCompiler(List<RoutePredicateFactory<?>> predicateFactories,
                                       List<GatewayFilterFactory<?>> gatewayFilterFactories,
                                       GatewayProperties gatewayProperties,
                                       WebFluxProperties webFluxProperties,
                                       RouteCompiler slowRouteCompiler) {
        return new FastRouteCompiler(
                predicateFactories,
                gatewayFilterFactories,
                gatewayProperties,
                webFluxProperties,
                slowRouteCompiler);
    }
}