package com.example.shared.routes;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

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
                "Echoes the request via httpbin for smoke testing."
        );
        RouteConfigEntry echo = new RouteConfigEntry(
                "echo-route",
                "/echo/**",
                "http://echo.svc.cluster.local:8080",
                null,
                null,
                true,
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
                List.of(new RouteConfigEntry("a", "/a", "http://a", 1, null, true, null, null))
        );

        assertThatThrownBy(() -> snapshot.routes().add(
                new RouteConfigEntry("b", "/b", "http://b", 1, null, true, null, null)
        )).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void methodsListIsUnmodifiable() {
        RouteConfigEntry entry = new RouteConfigEntry(
                "a", "/a", "http://a", 1, List.of("GET"), true, null, null
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
