package com.example.gateway.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
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

    private static final List<String> PACKAGES = List.of(
            "com/example/gateway/access",
            "com/example/gateway/audit",
            "com/example/gateway/routing",
            "com/example/gateway/routes");

    @Test
    void securityAgnosticPackagesDoNotImportSpringSecurity() throws IOException {
        Path src = Path.of("src/main/java");
        for (String pkg : PACKAGES) {
            try (Stream<Path> files = Files.walk(src.resolve(pkg))) {
                files.filter(f -> f.toString().endsWith(".java")).forEach(file -> {
                    try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
                        List<String> offending = lines
                                .filter(line -> line.startsWith("import "))
                                .filter(line -> line.contains("org.springframework.security")
                                        || line.contains("com.example.gateway.security"))
                                .toList();
                        assertThat(offending).as("%s must not depend on the security layer", file).isEmpty();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        }
    }
}
