package com.example.gateway.audit;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogAuditEventSinkTest {

    private final LogAuditEventSink sink = new LogAuditEventSink("dynamic-gateway", "gw-pod-1");

    @Test
    void writesOneEcsShapedObject() {
        AuditEvent event = new AuditEvent(Instant.parse("2026-10-27T17:03:12.345Z"), 12_345_678L,
                "route.access", true, "allowed",
                "sub-1", "alice", "alice@tools.local", "session", "tools-gateway", "sid-1",
                "kafka-ui", "platform-team", "authenticated", List.of("group:tools-kafka-ui"), "group:tools-kafka-ui",
                "kafka-ui.tools.svc.cluster.local:8080", "GET", "/kafka-ui/topics", null, "req-1", 200, false,
                "10.42.0.5", "203.0.113.7, 10.42.0.5", "Mozilla/5.0");

        String line = sink.toJson(event);
        JsonNode json = JsonMapper.builder().build().readTree(line);

        assertThat(line).doesNotContain("\n");
        assertThat(json.path("@timestamp").asString()).isEqualTo("2026-10-27T17:03:12.345Z");
        assertThat(json.path("event").path("action").asString()).isEqualTo("route.access");
        assertThat(json.path("event").path("outcome").asString()).isEqualTo("success");
        assertThat(json.path("event").path("duration").asLong()).isEqualTo(12_345_678L);
        assertThat(json.path("user").path("name").asString()).isEqualTo("alice");
        assertThat(json.path("gateway").path("auth").path("sid").asString()).isEqualTo("sid-1");
        assertThat(json.path("gateway").path("route").path("team").asString()).isEqualTo("platform-team");
        assertThat(json.path("gateway").path("access").path("required").get(0).asString()).isEqualTo("group:tools-kafka-ui");
        assertThat(json.path("gateway").path("client").path("forwarded_for").asString()).isEqualTo("203.0.113.7, 10.42.0.5");
        assertThat(json.path("http").path("response").path("status_code").asInt()).isEqualTo(200);
        assertThat(json.path("url").has("query")).isFalse();
        assertThat(json.path("host").path("name").asString()).isEqualTo("gw-pod-1");
    }

    @Test
    void anonymousEventHasNoUserObjectAndFailureOutcome() {
        AuditEvent event = new AuditEvent(Instant.now(), 1L, "route.access", false, "unauthenticated",
                null, null, null, "none", null, null, "default-route", null, "authenticated", List.of(), null,
                "upstream:8080", "POST", "/default/x", null, "req-2", 401, false, "10.0.0.1", null, null);

        JsonNode json = JsonMapper.builder().build().readTree(sink.toJson(event));

        assertThat(json.has("user")).isFalse();
        assertThat(json.path("event").path("outcome").asString()).isEqualTo("failure");
        assertThat(json.path("gateway").path("auth").path("method").asString()).isEqualTo("none");
        assertThat(json.path("gateway").path("access").has("required")).isFalse();
    }
}
