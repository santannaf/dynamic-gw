package com.example.gateway.routes.api;

import com.example.gateway.routes.GatewayRouteReloadService;
import com.example.gateway.routing.InMemoryDynamicRouteLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/internal/routes")
public class InternalRoutesController {

    private static final Logger log = LoggerFactory.getLogger(InternalRoutesController.class);

    private final GatewayRouteReloadService reloadService;
    private final InMemoryDynamicRouteLocator routeLocator;

    public InternalRoutesController(GatewayRouteReloadService reloadService,
                                    InMemoryDynamicRouteLocator routeLocator) {
        this.reloadService = reloadService;
        this.routeLocator = routeLocator;
    }

    @PostMapping("/reload")
    public Mono<ResponseEntity<Map<String, Object>>> reload() {
        log.info("Manual reload requested via /internal/routes/reload");
        return Mono.fromCallable(reloadService::reloadFromStore)
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .map(active -> ResponseEntity.ok(Map.of(
                        "status", "reloaded",
                        "routes", active
                )))
                .onErrorResume(ex -> {
                    log.warn("Reload via /internal/routes/reload failed: {}", ex.getMessage(), ex);
                    return Mono.just(ResponseEntity.internalServerError().body(Map.of(
                            "status", "error",
                            "error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()
                    )));
                });
    }

    @GetMapping
    public Mono<Map<String, Object>> list() {
        return Flux.fromIterable(routeLocator.currentDefinitions())
                .map(InternalRoutesController::view)
                .collectList()
                .map(routes -> Map.of("routes", routes));
    }

    private static Map<String, Object> view(RouteDefinition rd) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", rd.getId());
        assert rd.getUri() != null;
        map.put("uri", rd.getUri().toString());
        map.put("predicates", rd.getPredicates().stream()
                .map(p -> p.getName() + "=" + String.join(",", p.getArgs().values()))
                .collect(Collectors.toList()));
        map.put("filters", rd.getFilters().stream()
                .map(f -> f.getName() + "=" + String.join(",", f.getArgs().values()))
                .collect(Collectors.toList()));
        // Ownership metadata: present only if the source RouteConfigEntry
        // carried them. Absent keys mean "not declared" — easier for clients
        // to distinguish from empty-string than always emitting null.
        Object team = rd.getMetadata().get("team");
        if (team != null) {
            map.put("team", team);
        }
        Object description = rd.getMetadata().get("description");
        if (description != null) {
            map.put("description", description);
        }
        return map;
    }
}