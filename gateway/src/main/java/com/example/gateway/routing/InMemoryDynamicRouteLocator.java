package com.example.gateway.routing;

import org.jspecify.annotations.NonNull;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

@Component
public class InMemoryDynamicRouteLocator implements RouteLocator {

    private volatile List<Route> routes = List.of();
    private volatile List<RouteDefinition> definitions = List.of();

    @Override
    @NonNull
    public Flux<Route> getRoutes() { return Flux.fromIterable(routes); }

    public synchronized void replaceAll(List<Route> newRoutes, List<RouteDefinition> newDefinitions) {
        this.routes = List.copyOf(newRoutes);
        this.definitions = List.copyOf(newDefinitions);
    }

    public int size() { return routes.size(); }

    public List<RouteDefinition> currentDefinitions() { return definitions; }

    public List<Route> currentRoutes() { return routes; }
}