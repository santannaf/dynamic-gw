package com.example.operator;

import com.example.operator.reconcile.GatewayRouteReconciler;
import com.example.operator.reconcile.GatewayRouteValidator;
import com.example.operator.reconcile.SnapshotBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class StorageAbstractionLeakTest {

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "io.fabric8.kubernetes.api.model.ConfigMap",
            "io.fabric8.kubernetes.client.KubernetesClient",
            "software.amazon.awssdk",
            "com.amazonaws"
    );

    @Test
    void reconcilerAndSnapshotBuilderDoNotImportStorageTypes() {
        Path src = Path.of("src/main/java");
        List<Class<?>> classesToCheck = List.of(
                GatewayRouteReconciler.class,
                SnapshotBuilder.class,
                GatewayRouteValidator.class
        );

        for (Class<?> klass : classesToCheck) {
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
