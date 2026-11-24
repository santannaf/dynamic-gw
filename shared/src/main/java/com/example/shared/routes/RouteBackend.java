package com.example.shared.routes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One backend of an aggregated route. {@code url} may carry the route's path
 * variables ({@code http://pedidos/pedidos/{id}}). {@code group} puts this
 * backend's response under that field; without it, the fields are merged into
 * the response. {@code transform} applies to this backend's response only.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RouteBackend(
        String url,
        String group,
        RouteTransform transform
) {
}
