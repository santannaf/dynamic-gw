package com.example.shared.routes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Entra On-Behalf-Of of a route ({@code access.obo}): the backend receives the
 * Entra token the gateway gets for these scopes, such as
 * {@code api://backend-b/pedidos.read}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RouteObo(List<String> scopes) {
    public RouteObo {
        // Unlike List.copyOf, keeps a null entry, so RoutePolicyRules rejects the route
        // instead of the whole snapshot failing to load.
        scopes = scopes == null ? null : Collections.unmodifiableList(new ArrayList<>(scopes));
    }
}
