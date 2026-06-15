package com.example.gateway.routes;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GatewayRoutesBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GatewayRoutesBootstrap.class);

    private final GatewayRouteReloadService reloadService;

    public GatewayRoutesBootstrap(GatewayRouteReloadService reloadService) {
        this.reloadService = reloadService;
    }

    @Override
    public void run(@NonNull ApplicationArguments args) {
        try {
            List<String> active = reloadService.reloadFromStore();
            log.info("Gateway bootstrap completed activeRoutes={}", active.size());
        } catch (Exception e) {
            log.warn("Gateway bootstrap could not load routes; starting with empty route set: {}",
                    e.getMessage(), e);
        }
    }
}
