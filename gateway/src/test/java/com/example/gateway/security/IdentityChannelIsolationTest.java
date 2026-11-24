package com.example.gateway.security;

import com.example.gateway.security.keycloak.KeycloakSecurity;
import com.example.gateway.security.ldap.LdapSecurity;
import com.example.gateway.security.oauth.OAuthSecurity;
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

    // Taken from the classes, so the test follows a gateway that renames its packages.
    private static final List<String> CHANNELS = Stream.of(KeycloakSecurity.class, LdapSecurity.class, OAuthSecurity.class)
            .map(Class::getPackageName)
            .toList();

    @Test
    void onlyActiveSecurityNamesTheChannels() throws IOException {
        Path security = Path.of("src/main/java", ActiveSecurity.class.getPackageName().replace('.', '/'));
        assertThat(security).isDirectory();

        List<Path> sources;
        try (Stream<Path> files = Files.list(security)) {
            sources = files.filter(file -> file.toString().endsWith(".java")).toList();
        }
        assertThat(sources).as("sources in %s", security).isNotEmpty();

        for (Path file : sources) {
            if (file.endsWith(ActiveSecurity.class.getSimpleName() + ".java")) {
                continue;
            }
            List<String> offending = Files.readAllLines(file).stream()
                    .filter(line -> CHANNELS.stream().anyMatch(channel -> line.startsWith("import " + channel + ".")))
                    .toList();
            assertThat(offending).as("%s must not depend on a specific channel", file).isEmpty();
        }
    }
}
