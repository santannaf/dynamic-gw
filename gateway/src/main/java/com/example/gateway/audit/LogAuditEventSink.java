package com.example.gateway.audit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.json.JsonFactory;

import java.io.StringWriter;
import java.util.List;

/**
 * Writes each event as one JSON object per line on the {@code AUDIT} logger.
 * The logger gets its own message-only console appender with additivity off,
 * so audit lines are pure JSON (no timestamp/level prefix) and never show up
 * twice; operational logs keep their format, which scripts parse.
 * The appender is wired in code rather than in logback XML to stay
 * reflection-free in the native image.
 */
public class LogAuditEventSink implements AuditEventSink {

    public static final String LOGGER_NAME = "AUDIT";
    private static final String ECS_VERSION = "8.11";
    private static final JsonFactory JSON = JsonFactory.builder().build();

    private final org.slf4j.Logger audit;
    private final String serviceName;
    private final String hostName;

    public LogAuditEventSink(String serviceName, String hostName) {
        this.serviceName = serviceName;
        this.hostName = hostName;
        this.audit = LoggerFactory.getLogger(LOGGER_NAME);
        configureMessageOnlyAppender();
    }

    @Override
    public void emit(AuditEvent event) {
        audit.info(toJson(event));
    }

    String toJson(AuditEvent e) {
        StringWriter out = new StringWriter(512);
        try (JsonGenerator g = JSON.createGenerator(ObjectWriteContext.empty(), out)) {
            g.writeStartObject();
            g.writeStringProperty("@timestamp", e.timestamp().toString());
            g.writeName("ecs");
            g.writeStartObject();
            g.writeStringProperty("version", ECS_VERSION);
            g.writeEndObject();

            g.writeName("event");
            g.writeStartObject();
            g.writeStringProperty("kind", "event");
            g.writeName("category");
            g.writeStartArray();
            g.writeString("web");
            g.writeEndArray();
            g.writeStringProperty("action", e.action());
            g.writeStringProperty("outcome", e.allowed() ? "success" : "failure");
            g.writeStringProperty("reason", e.reason());
            g.writeNumberProperty("duration", e.durationNanos());
            g.writeEndObject();

            if (e.userId() != null || e.userName() != null) {
                g.writeName("user");
                g.writeStartObject();
                optional(g, "id", e.userId());
                optional(g, "name", e.userName());
                optional(g, "email", e.userEmail());
                g.writeEndObject();
            }

            g.writeName("gateway");
            g.writeStartObject();
            g.writeName("auth");
            g.writeStartObject();
            g.writeStringProperty("method", e.authMethod());
            optional(g, "client_id", e.clientId());
            optional(g, "sid", e.sessionId());
            g.writeEndObject();
            if (e.routeId() != null) {
                g.writeName("route");
                g.writeStartObject();
                g.writeStringProperty("id", e.routeId());
                optional(g, "team", e.routeTeam());
                g.writeEndObject();
            }
            if (e.accessMode() != null) {
                g.writeName("access");
                g.writeStartObject();
                g.writeStringProperty("mode", e.accessMode());
                strings(g, "required", e.requiredAuthorities());
                optional(g, "matched", e.matchedAuthority());
                g.writeEndObject();
            }
            if (e.upstreamHost() != null) {
                g.writeName("upstream");
                g.writeStartObject();
                g.writeStringProperty("host", e.upstreamHost());
                g.writeEndObject();
            }
            if (e.forwardedFor() != null || e.cancelled()) {
                g.writeName("client");
                g.writeStartObject();
                optional(g, "forwarded_for", e.forwardedFor());
                if (e.cancelled()) {
                    g.writeBooleanProperty("cancelled", true);
                }
                g.writeEndObject();
            }
            g.writeEndObject();

            g.writeName("http");
            g.writeStartObject();
            g.writeName("request");
            g.writeStartObject();
            g.writeStringProperty("method", e.method());
            optional(g, "id", e.requestId());
            g.writeEndObject();
            if (e.status() != null) {
                g.writeName("response");
                g.writeStartObject();
                g.writeNumberProperty("status_code", e.status());
                g.writeEndObject();
            }
            g.writeEndObject();

            g.writeName("url");
            g.writeStartObject();
            g.writeStringProperty("path", e.path());
            optional(g, "query", e.query());
            g.writeEndObject();

            if (e.sourceIp() != null) {
                g.writeName("source");
                g.writeStartObject();
                g.writeStringProperty("ip", e.sourceIp());
                g.writeEndObject();
            }
            if (e.userAgent() != null) {
                g.writeName("user_agent");
                g.writeStartObject();
                g.writeStringProperty("original", e.userAgent());
                g.writeEndObject();
            }
            g.writeName("service");
            g.writeStartObject();
            g.writeStringProperty("name", serviceName);
            g.writeEndObject();
            if (hostName != null) {
                g.writeName("host");
                g.writeStartObject();
                g.writeStringProperty("name", hostName);
                g.writeEndObject();
            }
            g.writeEndObject();
        }
        return out.toString();
    }

    private static void optional(JsonGenerator g, String name, String value) {
        if (value != null) {
            g.writeStringProperty(name, value);
        }
    }

    private static void strings(JsonGenerator g, String name, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        g.writeName(name);
        g.writeStartArray();
        for (String value : values) {
            g.writeString(value);
        }
        g.writeEndArray();
    }

    private static void configureMessageOnlyAppender() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            return;
        }
        Logger logger = context.getLogger(LOGGER_NAME);
        if (logger.getAppender(LOGGER_NAME) != null) {
            return;
        }
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%msg%n");
        encoder.start();
        ConsoleAppender<ILoggingEvent> appender = new ConsoleAppender<>();
        appender.setName(LOGGER_NAME);
        appender.setContext(context);
        appender.setEncoder(encoder);
        appender.start();
        logger.addAppender(appender);
        logger.setAdditive(false);
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
    }
}
