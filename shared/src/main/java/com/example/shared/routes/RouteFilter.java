package com.example.shared.routes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One filter of the route's chain, by catalog name ({@link RoutePolicyRules#FILTER_CATALOG})
 * and named arguments. Kept free of Spring Cloud Gateway types: the gateway
 * turns it into a filter definition.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RouteFilter(
        String name,
        Map<String, String> args
) {
    public RouteFilter {
        args = args == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(args));
    }
}
