package com.example.gateway.security.session;

import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Per-replica cache of resolved sessions, keyed by session id. Entries expire
 * at an instant chosen by the provider (access token expiry for Keycloak,
 * revalidation interval for LDAP). Loads are single-flight per key: ten
 * concurrent requests for an expired session trigger one call to the
 * provider. The cache is bounded; on overflow it drops expired entries first
 * and then the ones closest to expiring.
 */
public class SessionCache<V> {

    private final int maxEntries;
    private final Clock clock;
    private final Map<String, Entry<V>> entries = new ConcurrentHashMap<>();
    private final Map<String, Mono<V>> inFlight = new ConcurrentHashMap<>();

    public SessionCache(int maxEntries, Clock clock) {
        this.maxEntries = Math.max(1, maxEntries);
        this.clock = clock;
    }

    public Optional<V> getIfValid(String key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!entry.expiresAt().isAfter(clock.instant())) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    /**
     * Returns the cached value or runs {@code loader} once for all concurrent
     * callers of the same key. An empty or failed load caches nothing.
     */
    public Mono<V> getOrLoad(String key, Supplier<Mono<Loaded<V>>> loader) {
        Optional<V> cached = getIfValid(key);
        if (cached.isPresent()) {
            return Mono.just(cached.get());
        }
        return inFlight.computeIfAbsent(key, k -> Mono.defer(loader)
                .doOnNext(loaded -> put(k, loaded.value(), loaded.expiresAt()))
                .map(Loaded::value)
                .doFinally(signal -> inFlight.remove(k))
                .cache());
    }

    public void put(String key, V value, Instant expiresAt) {
        if (entries.size() >= maxEntries && !entries.containsKey(key)) {
            evictForSpace();
        }
        entries.put(key, new Entry<>(value, expiresAt));
    }

    public void evict(String key) {
        entries.remove(key);
    }

    public int size() {
        return entries.size();
    }

    private void evictForSpace() {
        Instant now = clock.instant();
        entries.entrySet().removeIf(e -> !e.getValue().expiresAt().isAfter(now));
        while (entries.size() >= maxEntries) {
            entries.entrySet().stream()
                    .min(Comparator.comparing(e -> e.getValue().expiresAt()))
                    .ifPresent(e -> entries.remove(e.getKey(), e.getValue()));
        }
    }

    public record Loaded<V>(V value, Instant expiresAt) {
    }

    private record Entry<V>(V value, Instant expiresAt) {
    }
}
