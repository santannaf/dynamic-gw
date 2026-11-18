package com.example.shared.routes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Access policy of one route, as declared in {@code GatewayRoute.spec.access}.
 *
 * {@code mode} stays a plain string ({@code public} | {@code authenticated})
 * so this module keeps parsing snapshots whose values it does not interpret;
 * the gateway owns the semantics and fails closed on unknown values.
 * {@code anyOf} holds authorities in the form {@code <type>:<name>}; the
 * gateway decides which types exist. {@code forwardToken == null} means
 * {@code false}.
 *
 * {@code channel} picks the identity channel whose session the route accepts
 * (absent: the gateway's provider). {@code identityProvider} is the alias of
 * the Keycloak identity provider the session must come from.
 * {@code forwardToken} sends a token to the backend, and {@code audience}
 * (token exchange for that client) or {@code obo} (Entra On-Behalf-Of) picks
 * which one; without either, the caller's own token goes.
 * {@link RoutePolicyRules} holds the rules between these fields.
 *
 * Only one constructor: a second one would stop Spring Boot from finding the
 * one to bind routes declared in properties.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RouteAccess(
        String mode,
        List<String> anyOf,
        Boolean forwardToken,
        String channel,
        String identityProvider,
        String audience,
        RouteObo obo
) {
    public RouteAccess {
        // Unlike List.copyOf, keeps a null entry, so RoutePolicyRules rejects the route
        // instead of the whole snapshot failing to load.
        anyOf = anyOf == null ? null : Collections.unmodifiableList(new ArrayList<>(anyOf));
    }
}
