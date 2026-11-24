package com.example.gateway.routes.store;

import com.example.shared.routes.RouteConfigEntry;
import com.example.shared.routes.RouteConfigSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

public class PropertiesRouteConfigProvider implements RouteConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(PropertiesRouteConfigProvider.class);

    private final RouteConfigSnapshot cached;

    public PropertiesRouteConfigProvider(RouteStoreProperties properties) {
        RouteStoreProperties.Properties cfg = properties.getProperties();
        String version = cfg.getVersion() == null || cfg.getVersion().isBlank()
                ? "static"
                : cfg.getVersion();
        List<RouteConfigEntry> routes = cfg.getRoutes() == null
                ? List.of()
                : List.copyOf(cfg.getRoutes());
        this.cached = new RouteConfigSnapshot(version, Instant.now(), routes);
        log.info("Properties route config provider initialized version={} routes={}", version, routes.size());
    }

    @Override
    public RouteConfigSnapshot load() {
        return cached;
    }
}
