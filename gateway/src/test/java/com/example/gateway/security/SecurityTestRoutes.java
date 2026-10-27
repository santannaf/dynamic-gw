package com.example.gateway.security;

import org.springframework.test.context.DynamicPropertyRegistry;

/** Routes shared by the security integration tests, served from properties (v1 store). */
final class SecurityTestRoutes {

    private SecurityTestRoutes() {
    }

    static void register(DynamicPropertyRegistry registry, String upstream) {
        registry.add("gateway.routes.store.type", () -> "properties");
        route(registry, 0, "public-route", "/public/**", upstream, 1);
        registry.add("gateway.routes.store.properties.routes[0].access.mode", () -> "public");
        route(registry, 1, "default-route", "/default/**", upstream, 1);
        route(registry, 2, "group-route", "/group/**", upstream, 1);
        registry.add("gateway.routes.store.properties.routes[2].team", () -> "platform-team");
        registry.add("gateway.routes.store.properties.routes[2].access.any-of[0]", () -> "group:tools-httpbin");
        route(registry, 3, "token-route", "/token/**", upstream, 1);
        registry.add("gateway.routes.store.properties.routes[3].access.any-of[0]", () -> "group:tools-httpbin");
        registry.add("gateway.routes.store.properties.routes[3].access.forward-token", () -> "true");
        route(registry, 4, "admin-route", "/admin/**", upstream, 1);
        registry.add("gateway.routes.store.properties.routes[4].access.any-of[0]", () -> "role:gateway-admin");
        // Tools often have their own /login and /logout; Spring Security must not shadow them.
        route(registry, 5, "login-route", "/login/**", upstream, 0);
        registry.add("gateway.routes.store.properties.routes[5].access.mode", () -> "public");
        route(registry, 6, "logout-route", "/logout/**", upstream, 0);
        registry.add("gateway.routes.store.properties.routes[6].access.mode", () -> "public");
    }

    private static void route(DynamicPropertyRegistry registry, int index, String id, String path,
                              String upstream, int stripPrefix) {
        String prefix = "gateway.routes.store.properties.routes[" + index + "].";
        registry.add(prefix + "id", () -> id);
        registry.add(prefix + "path", () -> path);
        registry.add(prefix + "target-uri", () -> upstream);
        registry.add(prefix + "strip-prefix", () -> String.valueOf(stripPrefix));
        registry.add(prefix + "enabled", () -> "true");
    }
}
