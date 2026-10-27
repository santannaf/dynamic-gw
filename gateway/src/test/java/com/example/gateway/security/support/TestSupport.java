package com.example.gateway.security.support;

import com.example.gateway.audit.AuditEvent;
import com.example.gateway.audit.AuditEventSink;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

public final class TestSupport {

    /** Base64 of 32 fixed bytes: a valid A256GCM key for tests. */
    public static final String KEY_1 = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    public static final String KEY_2 = Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes());

    private TestSupport() {
    }

    /** Value of the last {@code Set-Cookie} with this name, as a browser would keep it. */
    public static String cookie(EntityExchangeResult<?> result, String name) {
        ResponseCookie cookie = last(result, name);
        return cookie == null || Duration.ZERO.equals(cookie.getMaxAge()) ? null : cookie.getValue();
    }

    public static boolean expires(EntityExchangeResult<?> result, String name) {
        ResponseCookie cookie = last(result, name);
        return cookie != null && Duration.ZERO.equals(cookie.getMaxAge());
    }

    private static ResponseCookie last(EntityExchangeResult<?> result, String name) {
        List<ResponseCookie> cookies = result.getResponseCookies().get(name);
        return cookies == null || cookies.isEmpty() ? null : cookies.getLast();
    }

    public static Map<String, String> query(String uri) {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        int q = uri.indexOf('?');
        if (q < 0) {
            return params;
        }
        for (String pair : uri.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(pair.substring(0, eq),
                        java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    /** Collects audit events in memory. */
    public static final class RecordingSink implements AuditEventSink {

        private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void emit(AuditEvent event) {
            events.add(event);
        }

        public List<AuditEvent> events() {
            return new ArrayList<>(events);
        }

        public List<AuditEvent> matching(Predicate<AuditEvent> filter) {
            return events.stream().filter(filter).toList();
        }

        /** Events are emitted when the server finishes the exchange, which can trail the client's view. */
        public AuditEvent awaitLast(Predicate<AuditEvent> filter) {
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5))
                    .until(() -> events.stream().anyMatch(filter));
            return matching(filter).getLast();
        }

        public AuditEvent awaitPath(String path) {
            return awaitLast(e -> path.equals(e.path()));
        }

        public void clear() {
            events.clear();
        }
    }

    /** Clock tests can move forward to expire cached sessions. */
    public static final class MutableClock extends Clock {

        private volatile Instant now = Instant.now();

        public void advance(Duration duration) {
            now = now.plus(duration);
        }

        public void reset() {
            now = Instant.now();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
