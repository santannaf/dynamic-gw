package com.example.shared.routes;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotCodecAccessTest {

    private final SnapshotCodec codec = new SnapshotCodec();

    @Test
    void newAccessFieldsSurviveYamlJsonAndGzip() {
        RouteAccess access = new RouteAccess(null, List.of("role:pedidos-leitura"), true, "keycloak", "microsoft",
                null, new RouteObo(List.of("api://backend-b/pedidos.read")));
        RouteConfigSnapshot snapshot = new RouteConfigSnapshot("v1", Instant.parse("2026-11-18T21:00:00Z"),
                List.of(new RouteConfigEntry("pedidos", "/pedidos/**", "http://backend-b", 1, null, true, null, null,
                        access, null, null, null, null, null, null)));

        assertThat(codec.readYaml(codec.writeYaml(snapshot)).routes().getFirst().access()).isEqualTo(access);
        assertThat(codec.readYamlGz(codec.writeYamlGz(snapshot)).routes().getFirst().access()).isEqualTo(access);
        assertThat(codec.writeJson(snapshot)).contains("\"identityProvider\":\"microsoft\"",
                "\"obo\":{\"scopes\":[\"api://backend-b/pedidos.read\"]}");
    }

    @Test
    void snapshotWrittenBeforeTheNewFieldsStillReads() {
        String yaml = """
                version: "v0"
                generatedAt: "2026-11-01T00:00:00Z"
                routes:
                - id: "abc"
                  path: "/abc/**"
                  targetUri: "http://abc"
                  access:
                    mode: null
                    anyOf:
                    - "role:abc-user"
                    forwardToken: true
                """;

        RouteAccess access = codec.readYaml(yaml).routes().getFirst().access();

        assertThat(access).isEqualTo(new RouteAccess(null, List.of("role:abc-user"), true, null, null, null, null));
    }

    @Test
    void audienceIsReadFromTheSnapshot() {
        String yaml = """
                version: "v1"
                generatedAt: "2026-11-18T21:00:00Z"
                routes:
                - id: "abc"
                  path: "/abc/**"
                  targetUri: "http://abc"
                  access:
                    anyOf:
                    - "role:abc-user"
                    forwardToken: true
                    audience: "abc-api"
                """;

        assertThat(codec.readYaml(yaml).routes().getFirst().access().audience()).isEqualTo("abc-api");
    }
}
