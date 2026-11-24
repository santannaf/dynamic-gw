package com.example.gateway.security.ldap;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-replica sliding window of failed logins, counted per username and per
 * client address. The username counter is what protects accounts (the
 * address comes from {@code X-Forwarded-For} and can be forged); the client
 * counter has a higher threshold because many users can share one address
 * behind NAT. The directory's own lockout policy still applies on top.
 */
public class LoginAttemptLimiter {

    private static final int MAX_KEYS = 50_000;

    private final int maxFailuresPerUser;
    private final int maxFailuresPerClient;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public LoginAttemptLimiter(int maxFailuresPerUser, int maxFailuresPerClient, Duration window, Clock clock) {
        this.maxFailuresPerUser = Math.max(1, maxFailuresPerUser);
        this.maxFailuresPerClient = Math.max(1, maxFailuresPerClient);
        this.window = window;
        this.clock = clock;
    }

    public boolean isBlocked(String username, String client) {
        return exceeded(userKey(username), maxFailuresPerUser) || exceeded(clientKey(client), maxFailuresPerClient);
    }

    public void recordFailure(String username, String client) {
        if (failures.size() >= MAX_KEYS) {
            prune();
        }
        record(userKey(username));
        record(clientKey(client));
    }

    public void reset(String username) {
        String key = userKey(username);
        if (key != null) {
            failures.remove(key);
        }
    }

    private boolean exceeded(String key, int maxFailures) {
        if (key == null) {
            return false;
        }
        Deque<Instant> attempts = failures.get(key);
        if (attempts == null) {
            return false;
        }
        synchronized (attempts) {
            dropOld(attempts);
            return attempts.size() >= maxFailures;
        }
    }

    private void record(String key) {
        if (key == null) {
            return;
        }
        Deque<Instant> attempts = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (attempts) {
            dropOld(attempts);
            attempts.addLast(clock.instant());
        }
    }

    private void dropOld(Deque<Instant> attempts) {
        Instant cutoff = clock.instant().minus(window);
        while (!attempts.isEmpty() && !attempts.peekFirst().isAfter(cutoff)) {
            attempts.removeFirst();
        }
    }

    private void prune() {
        failures.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                dropOld(e.getValue());
                return e.getValue().isEmpty();
            }
        });
    }

    private static String userKey(String username) {
        return username == null || username.isBlank() ? null : "u:" + username.trim().toLowerCase(Locale.ROOT);
    }

    private static String clientKey(String client) {
        return client == null || client.isBlank() ? null : "c:" + client;
    }
}
