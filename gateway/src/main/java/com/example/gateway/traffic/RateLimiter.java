package com.example.gateway.traffic;

import com.example.shared.routes.RoutePolicyRules;
import com.example.shared.routes.RouteRateLimit;

import java.util.concurrent.atomic.AtomicLong;

/**
 * One request budget, kept as a GCRA (generic cell rate algorithm): a single
 * "theoretical arrival time" updated with compare-and-set, so it never blocks
 * the event loop. Requests are spaced {@code period / requests} apart, and up
 * to {@code burst} may arrive at once.
 */
final class RateLimiter {

    private final long limit;
    private final long periodNanos;
    private final long intervalNanos;
    private final long burstNanos;
    private final AtomicLong theoreticalArrival;

    RateLimiter(RouteRateLimit policy, long nowNanos) {
        this.limit = policy.requests();
        this.periodNanos = RoutePolicyRules.parseDuration(policy.period()).toNanos();
        this.intervalNanos = Math.max(1, periodNanos / limit);
        int burst = policy.burst() == null ? policy.requests() : policy.burst();
        this.burstNanos = intervalNanos * burst;
        this.theoreticalArrival = new AtomicLong(nowNanos);
    }

    Decision tryAcquire(long nowNanos) {
        while (true) {
            long arrival = theoreticalArrival.get();
            long next = Math.max(arrival, nowNanos) + intervalNanos;
            long fitsAt = next - burstNanos;
            if (nowNanos < fitsAt) {
                return new Decision(false, limit, periodNanos, 0, Math.max(0, arrival - nowNanos), fitsAt - nowNanos);
            }
            if (theoreticalArrival.compareAndSet(arrival, next)) {
                long remaining = (burstNanos - (next - nowNanos)) / intervalNanos;
                return new Decision(true, limit, periodNanos, remaining, next - nowNanos, 0);
            }
        }
    }

    /** True when the budget is full again: dropping it changes nothing. */
    boolean idle(long nowNanos) {
        return theoreticalArrival.get() <= nowNanos;
    }

    /**
     * {@code resetNanos}: until the budget is full again. {@code retryAfterNanos}:
     * until a refused request would fit (0 when allowed).
     */
    record Decision(boolean allowed, long limit, long periodNanos, long remaining, long resetNanos,
                    long retryAfterNanos) {
    }
}
