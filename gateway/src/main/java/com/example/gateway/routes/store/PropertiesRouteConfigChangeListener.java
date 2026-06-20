package com.example.gateway.routes.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PropertiesRouteConfigChangeListener implements RouteConfigChangeListener {

    private static final Logger log = LoggerFactory.getLogger(PropertiesRouteConfigChangeListener.class);

    @Override
    public void start(Runnable onChange) {
        log.info("Properties mode: change listener is a no-op (routes are static)");
    }

    @Override
    public void stop() {
        // no-op: nothing to release; no watcher was registered.
    }
}
