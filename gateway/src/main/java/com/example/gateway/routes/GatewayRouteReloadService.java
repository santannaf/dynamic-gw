package com.example.gateway.routes;

import com.example.gateway.routing.InMemoryDynamicRouteDefinitionRepository;
import com.example.gateway.routing.RouteDefinitionMapper;
import com.example.gateway.routes.store.RouteConfigProvider;
import com.example.shared.routes.RouteConfigEntry;
import com.example.shared.routes.RouteConfigSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class GatewayRouteReloadService {

    private static final Logger log = LoggerFactory.getLogger(GatewayRouteReloadService.class);

    private final RouteConfigProvider provider;
    private final RouteDefinitionMapper mapper;
    private final InMemoryDynamicRouteDefinitionRepository repository;
    private final ApplicationEventPublisher events;

    public GatewayRouteReloadService(RouteConfigProvider provider,
                                     RouteDefinitionMapper mapper,
                                     InMemoryDynamicRouteDefinitionRepository repository,
                                     ApplicationEventPublisher events) {
        this.provider = provider;
        this.mapper = mapper;
        this.repository = repository;
        this.events = events;
    }

    public List<String> reloadFromStore() {
        log.info("Loading routes from route config store");
        RouteConfigSnapshot snapshot = provider.load();
        log.info("Route config snapshot loaded version={} routes={}",
                snapshot.version(), snapshot.routes().size());

        List<RouteDefinition> definitions = snapshot.routes().stream()
                .map(this::mapAndLog)
                .toList();

        repository.replaceAll(definitions);
        log.info("Routes replaced in memory count={}", definitions.size());

        events.publishEvent(new RefreshRoutesEvent(this));
        log.info("RefreshRoutesEvent published");

        return definitions.stream()
                .map(RouteDefinition::getId)
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private RouteDefinition mapAndLog(RouteConfigEntry entry) {
        log.info("Route loaded id={} path={} targetUri={}",
                entry.id(), entry.path(), entry.targetUri());
        return mapper.toDefinition(entry);
    }
}
