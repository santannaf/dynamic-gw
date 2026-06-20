package com.example.gateway.routes;

import com.example.gateway.routes.api.InternalRoutesController;
import com.example.gateway.routes.store.PropertiesRouteConfigChangeListener;
import com.example.gateway.routes.store.PropertiesRouteConfigProvider;
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
 * Reload pipeline classes must NOT import Kubernetes or AWS storage types directly.
 * They MUST depend only on the RouteConfigProvider abstraction.
 *
 * The {@link PropertiesRouteConfigProvider} + {@link PropertiesRouteConfigChangeListener}
 * pair backs the v1 standalone deploy and must additionally stay free of any
 * Kubernetes/AWS imports — the standalone deploy runs without apiserver access
 * or AWS credentials, so an accidental import would crash the startup.
 */
class StorageAbstractionLeakTest {

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "io.fabric8.kubernetes",
            "software.amazon.awssdk",
            "com.amazonaws"
    );

    @Test
    void reloadPipelineClassesDoNotImportStorageTypes() {
        assertNoForbiddenImports(List.of(
                GatewayRouteReloadService.class,
                GatewayRoutesBootstrap.class,
                InternalRoutesController.class
        ));
    }

    @Test
    void propertiesBackendClassesDoNotImportKubernetesOrAwsTypes() {
        assertNoForbiddenImports(List.of(
                PropertiesRouteConfigProvider.class,
                PropertiesRouteConfigChangeListener.class
        ));
    }

    private static void assertNoForbiddenImports(List<Class<?>> classes) {
        Path src = Path.of("src/main/java");
        for (Class<?> klass : classes) {
            String relative = klass.getName().replace('.', '/') + ".java";
            Path file = src.resolve(relative);
            assertThat(file).as("source file for %s", klass.getName()).exists();

            try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
                List<String> offending = lines
                        .filter(line -> line.startsWith("import "))
                        .filter(line -> FORBIDDEN_IMPORTS.stream().anyMatch(line::contains))
                        .toList();
                assertThat(offending)
                        .as("%s must not import storage-backend-specific types", klass.getSimpleName())
                        .isEmpty();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
