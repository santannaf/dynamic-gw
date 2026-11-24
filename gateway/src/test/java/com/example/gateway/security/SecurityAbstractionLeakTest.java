package com.example.gateway.security;

import com.example.gateway.access.ClientAddress;
import com.example.gateway.audit.AccessAuditWebFilter;
import com.example.gateway.routes.GatewayRouteReloadService;
import com.example.gateway.routing.InMemoryDynamicRouteLocator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Routing, the reload pipeline, route access enforcement and audit depend on
 * {@code GatewayIdentity} and exchange attributes, never on Spring Security:
 * the identity providers can change without touching them, the same way
 * storage backends hide behind {@code RouteConfigProvider}.
 */
class SecurityAbstractionLeakTest {

    // Taken from the classes, so the test follows a gateway that renames its packages.
    private static final List<String> PACKAGES = Stream.of(ClientAddress.class, AccessAuditWebFilter.class,
                    InMemoryDynamicRouteLocator.class, GatewayRouteReloadService.class)
            .map(Class::getPackageName)
            .toList();
    private static final String SECURITY = ActiveSecurity.class.getPackageName();

    @Test
    void securityAgnosticPackagesDoNotImportSpringSecurity() throws IOException {
        Path src = Path.of("src/main/java");
        for (String pkg : PACKAGES) {
            Path folder = src.resolve(pkg.replace('.', '/'));
            assertThat(folder).isDirectory();

            List<Path> sources;
            try (Stream<Path> files = Files.walk(folder)) {
                sources = files.filter(file -> file.toString().endsWith(".java")).toList();
            }
            assertThat(sources).as("sources in %s", folder).isNotEmpty();

            for (Path file : sources) {
                try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
                    List<String> offending = lines
                            .filter(line -> line.startsWith("import "))
                            .filter(line -> line.contains("org.springframework.security") || line.contains(SECURITY))
                            .toList();
                    assertThat(offending).as("%s must not depend on the security layer", file).isEmpty();
                }
            }
        }
    }
}
