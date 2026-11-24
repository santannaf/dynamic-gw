package com.example.gateway.traffic;

import com.example.shared.routes.RouteRateLimit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The budgets of this replica, one per (scope, policy, caller). A route that
 * changes its policy starts a fresh budget, and budgets that refilled are
 * dropped when per-caller budgets reach {@code maxKeys}. Past that, a new
 * caller is not limited on its own (the route-wide and global budgets still
 * hold): refusing everyone because the map is full would hand a DoS to whoever
 * fills it.
 */
final class RateLimiters {

    private static final Logger log = LoggerFactory.getLogger(RateLimiters.class);
    private static final long FULL_WARNING_INTERVAL_NANOS = 60_000_000_000L;

    private final ConcurrentHashMap<Key, RateLimiter> limiters = new ConcurrentHashMap<>();
    private final int maxKeys;
    private final LongSupplier nanoClock;
    private final AtomicLong lastFullWarning = new AtomicLong(Long.MIN_VALUE);

    RateLimiters(int maxKeys, LongSupplier nanoClock) {
        this.maxKeys = maxKeys;
        this.nanoClock = nanoClock;
    }

    /** Empty when the request could not be checked (per-caller budgets are full). */
    Optional<RateLimiter.Decision> tryAcquire(String scope, RouteRateLimit policy, String caller) {
        long now = nanoClock.getAsLong();
        Key key = new Key(scope, policy, caller);
        RateLimiter limiter = limiters.get(key);
        if (limiter == null) {
            if (caller != null && limiters.size() >= maxKeys) {
                limiters.values().removeIf(candidate -> candidate.idle(now));
                if (limiters.size() >= maxKeys) {
                    warnFull(now);
                    return Optional.empty();
                }
            }
            limiter = limiters.computeIfAbsent(key, k -> new RateLimiter(policy, now));
        }
        return Optional.of(limiter.tryAcquire(now));
    }

    int size() {
        return limiters.size();
    }

    private void warnFull(long now) {
        long last = lastFullWarning.get();
        if (now - last > FULL_WARNING_INTERVAL_NANOS && lastFullWarning.compareAndSet(last, now)) {
            log.warn("Rate limit budgets are full (gateway.traffic.rate-limit.max-keys={}); "
                    + "new callers are not limited per caller until budgets refill", maxKeys);
        }
    }

    private record Key(String scope, RouteRateLimit policy, String caller) {
    }
}
