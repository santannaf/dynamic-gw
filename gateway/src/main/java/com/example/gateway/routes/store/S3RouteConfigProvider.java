package com.example.gateway.routes.store;

import com.example.shared.routes.RouteConfigSnapshot;

public class S3RouteConfigProvider implements RouteConfigProvider {

    @Override
    public RouteConfigSnapshot load() {
        throw new UnsupportedOperationException(
                "S3 route config store is not implemented in this POC");
    }
}
