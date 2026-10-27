package com.example.shared.routes;

import java.util.List;

/**
 * Access policy of one route, as declared in {@code GatewayRoute.spec.access}.
 *
 * {@code mode} stays a plain string ({@code public} | {@code authenticated})
 * so this module keeps parsing snapshots whose values it does not interpret;
 * the gateway owns the semantics and fails closed on unknown values.
 * {@code anyOf} holds authorities in the form {@code group:<name>} or
 * {@code role:<name>}. {@code forwardToken == null} means {@code false}.
 */
public record RouteAccess(
        String mode,
        List<String> anyOf,
        Boolean forwardToken
) {
    public RouteAccess {
        anyOf = anyOf == null ? null : List.copyOf(anyOf);
    }
}
