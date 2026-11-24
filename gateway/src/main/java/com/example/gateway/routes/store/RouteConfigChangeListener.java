package com.example.gateway.routes.store;

public interface RouteConfigChangeListener {
    void start(Runnable onChange);
    void stop();
}