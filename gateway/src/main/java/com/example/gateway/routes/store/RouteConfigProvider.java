package com.example.gateway.routes.store;

import com.example.shared.routes.RouteConfigSnapshot;

public interface RouteConfigProvider {
    RouteConfigSnapshot load();
}