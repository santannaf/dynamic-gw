package com.example.operator.store;

import com.example.shared.routes.RouteConfigSnapshot;

public interface RouteConfigPublisher {
    void publish(RouteConfigSnapshot snapshot);
}
