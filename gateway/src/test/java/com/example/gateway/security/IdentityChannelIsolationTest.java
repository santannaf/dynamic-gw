package com.example.gateway.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ActiveSecurity} is the only class that names a channel. Anything else
 * in this package that imported one would make the next channel touch shared
 * code again.
 */
class IdentityChannelIsolationTest {

    @Test
    void onlyActiveSecurityNamesTheChannels() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/main/java/com/example/gateway/security"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (file.endsWith("ActiveSecurity.java")) {
                    continue;
                }
                List<String> offending = Files.readAllLines(file).stream()
                        .filter(line -> line.startsWith("import com.example.gateway.security.keycloak")
                                || line.startsWith("import com.example.gateway.security.ldap"))
                        .toList();
                assertThat(offending).as("%s must not depend on a specific channel", file).isEmpty();
            }
        }
    }
}
