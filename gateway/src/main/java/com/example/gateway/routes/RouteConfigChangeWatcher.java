package com.example.gateway.routes;

import com.example.gateway.routes.store.RouteConfigChangeListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

public class RouteConfigChangeWatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RouteConfigChangeWatcher.class);

    private final RouteConfigChangeListener listener;
    private final GatewayRouteReloadService reloadService;
    private volatile boolean running;

    public RouteConfigChangeWatcher(RouteConfigChangeListener listener,
                                    GatewayRouteReloadService reloadService) {
        this.listener = listener;
        this.reloadService = reloadService;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        listener.start(this::reload);
        running = true;
        log.info("Route config change watcher started");
    }

    @Override
    public synchronized void stop() {
        if (!running) return;
        listener.stop();
        running = false;
        log.info("Route config change watcher stopped");
    }

    @Override
    public boolean isRunning() { return running; }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000;
    }

    private void reload() {
        try {
            reloadService.reloadFromStore();
        } catch (RuntimeException e) {
            log.warn("Reload triggered by route config change listener failed: {}", e.getMessage(), e);
        }
    }
}