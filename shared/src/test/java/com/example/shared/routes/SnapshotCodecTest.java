package com.example.shared.routes;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotCodecTest {

    private final SnapshotCodec codec = new SnapshotCodec();

    @Test
    void roundTripPreservesAllFields() {
        RouteConfigEntry httpbin = new RouteConfigEntry(
                "httpbin-route",
                "/httpbin/**",
                "http://httpbin.org",
                1,
                List.of("GET", "POST"),
                true,
                "platform-team",
                "Echoes the request via httpbin for smoke testing.",
                new RouteAccess("authenticated", List.of("group:tools-httpbin", "role:gateway-admin"), true)
        );
        RouteConfigEntry echo = new RouteConfigEntry(
                "echo-route",
                "/echo/**",
                "http://echo.svc.cluster.local:8080",
                null,
                null,
                true,
                null,
                null,
                null
        );
        RouteConfigSnapshot original = new RouteConfigSnapshot(
                "2026-06-13T10:00:00Z",
                Instant.parse("2026-06-13T10:00:00Z"),
                List.of(httpbin, echo)
        );

        String yaml = codec.writeYaml(original);
        RouteConfigSnapshot restored = codec.readYaml(yaml);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void deserializeToleratesMissingOptionalFields() {
        String yaml = """
                version: "v1"
                generatedAt: "2026-06-13T10:00:00Z"
                routes:
                  - id: minimal
                    path: /foo/**
                    targetUri: http://upstream
                """;

        RouteConfigSnapshot snapshot = codec.readYaml(yaml);

        assertThat(snapshot.routes()).hasSize(1);
        RouteConfigEntry entry = snapshot.routes().get(0);
        assertThat(entry.id()).isEqualTo("minimal");
        assertThat(entry.path()).isEqualTo("/foo/**");
        assertThat(entry.targetUri()).isEqualTo("http://upstream");
        assertThat(entry.stripPrefix()).isNull();
        assertThat(entry.methods()).isNull();
        assertThat(entry.enabled()).isNull();
        assertThat(entry.team()).isNull();
        assertThat(entry.description()).isNull();
        assertThat(entry.access()).isNull();
    }

    @Test
    void deserializeAccessWithOnlyAnyOf() {
        String yaml = """
                version: "v1"
                generatedAt: "2026-06-13T10:00:00Z"
                routes:
                  - id: restricted
                    path: /foo/**
                    targetUri: http://upstream
                    access:
                      anyOf: [group:tools-httpbin]
                """;

        RouteAccess access = codec.readYaml(yaml).routes().getFirst().access();

        assertThat(access.mode()).isNull();
        assertThat(access.anyOf()).containsExactly("group:tools-httpbin");
        assertThat(access.forwardToken()).isNull();
    }

    @Test
    void gzippedSnapshotWithoutAccessStillDecodes() throws IOException {
        String yamlWrittenBeforeAccessExisted = """
                version: "2026-06-13T10:00:00Z"
                generatedAt: "2026-06-13T10:00:00Z"
                routes:
                  - id: httpbin-route
                    path: /httpbin/**
                    targetUri: http://httpbin.org
                    stripPrefix: 1
                    methods: [GET]
                    enabled: true
                    team: platform-team
                    description: Echo
                """;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(buffer)) {
            gz.write(yamlWrittenBeforeAccessExisted.getBytes(StandardCharsets.UTF_8));
        }

        RouteConfigSnapshot snapshot = codec.readYamlGz(buffer.toByteArray());

        assertThat(snapshot.routes()).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.team()).isEqualTo("platform-team");
                    assertThat(entry.access()).isNull();
                });
    }

    @Test
    void accessAnyOfIsUnmodifiable() {
        RouteAccess access = new RouteAccess(null, new java.util.ArrayList<>(List.of("group:a")), null);

        assertThatThrownBy(() -> access.anyOf().add("group:b"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void deserializeIgnoresUnknownTopLevelKey() {
        String yaml = """
                version: "v1"
                generatedAt: "2026-06-13T10:00:00Z"
                signature: "abc-not-a-real-field"
                routes: []
                """;

        RouteConfigSnapshot snapshot = codec.readYaml(yaml);

        assertThat(snapshot.version()).isEqualTo("v1");
        assertThat(snapshot.routes()).isEmpty();
    }

    @Test
    void routesListIsUnmodifiable() {
        RouteConfigSnapshot snapshot = new RouteConfigSnapshot(
                "v1",
                Instant.now(),
                List.of(new RouteConfigEntry("a", "/a", "http://a", 1, null, true, null, null, null))
        );

        assertThatThrownBy(() -> snapshot.routes().add(
                new RouteConfigEntry("b", "/b", "http://b", 1, null, true, null, null, null)
        )).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void methodsListIsUnmodifiable() {
        RouteConfigEntry entry = new RouteConfigEntry(
                "a", "/a", "http://a", 1, List.of("GET"), true, null, null,
 null
        );

        assertThatThrownBy(() -> entry.methods().add("POST"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptySnapshotHasNonNullFields() {
        RouteConfigSnapshot empty = RouteConfigSnapshot.empty();
        assertThat(empty.version()).isNotNull();
        assertThat(empty.generatedAt()).isNotNull();
        assertThat(empty.routes()).isNotNull().isEmpty();
    }

    @Test
    void yamlOutputUsesIso8601Instant() {
        RouteConfigSnapshot snapshot = new RouteConfigSnapshot(
                "v1",
                Instant.parse("2026-06-13T10:00:00Z"),
                List.of()
        );

        String yaml = codec.writeYaml(snapshot);

        assertThat(yaml).contains("2026-06-13T10:00:00Z");
        assertThat(yaml).doesNotContain("1781612800");
    }
}
