package com.example.operator.store;

import com.example.shared.routes.RouteConfigSnapshot;

public class S3RouteConfigPublisher implements RouteConfigPublisher {

    @Override
    public void publish(RouteConfigSnapshot snapshot) {
        throw new UnsupportedOperationException(
                "S3 route config store is not implemented in this POC");
    }
}
