package com.example.gateway.routing;

import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class InMemoryDynamicRouteDefinitionRepository implements RouteDefinitionRepository {

    private volatile Map<String, RouteDefinition> routes = Map.of();

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        return Flux.fromIterable(routes.values());
    }

    @Override
    public Mono<Void> save(Mono<RouteDefinition> route) {
        return route.doOnNext(this::upsert).then();
    }

    @Override
    public Mono<Void> delete(Mono<String> routeId) {
        return routeId.doOnNext(this::removeById).then();
    }

    public synchronized void replaceAll(Collection<RouteDefinition> nextRoutes) {
        Map<String, RouteDefinition> next = new LinkedHashMap<>(nextRoutes.size());
        for (RouteDefinition rd : nextRoutes) {
            next.put(rd.getId(), rd);
        }
        this.routes = Map.copyOf(next);
    }

    public int size() {
        return routes.size();
    }

    private synchronized void upsert(RouteDefinition rd) {
        Map<String, RouteDefinition> next = new LinkedHashMap<>(routes);
        next.put(rd.getId(), rd);
        this.routes = Map.copyOf(next);
    }

    private synchronized void removeById(String id) {
        if (!routes.containsKey(id)) {
            return;
        }
        Map<String, RouteDefinition> next = new LinkedHashMap<>(routes);
        next.remove(id);
        this.routes = Map.copyOf(next);
    }
}
