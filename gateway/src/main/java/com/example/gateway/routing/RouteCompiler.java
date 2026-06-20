package com.example.gateway.routing;

import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteDefinition;

import java.util.List;

@FunctionalInterface
public interface RouteCompiler {
    List<Route> compile(List<RouteDefinition> definitions);
}