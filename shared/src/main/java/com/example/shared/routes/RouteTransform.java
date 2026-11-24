package com.example.shared.routes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Changes to a JSON response, applied in this order (the same as KrakenD):
 * {@code target} keeps only the object under that field, {@code allow} keeps
 * only the listed fields or {@code deny} drops them, {@code mapping} renames
 * fields, and {@code group} wraps the result under one field.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RouteTransform(
        List<String> allow,
        List<String> deny,
        Map<String, String> mapping,
        String target,
        String group
) {
    public RouteTransform {
        allow = allow == null ? null : List.copyOf(allow);
        deny = deny == null ? null : List.copyOf(deny);
        mapping = mapping == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(mapping));
    }
}
