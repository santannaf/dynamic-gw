package com.example.gateway.traffic;

import com.example.shared.routes.RoutePolicyRules;
import com.example.shared.routes.RouteThrottle;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * At most {@code maxConcurrent} permits out at once. When they are all out, up
 * to {@code maxQueue} callers wait (without holding a thread) for at most
 * {@code maxWait}; a released permit goes straight to the oldest waiter. The
 * rest are refused with {@link Rejected}.
 *
 * Each caller's permit moves through one atomic state: waiting, granted, then
 * either delivered (the caller owns it and releases it) or given up (the
 * limiter takes it back). A cancellation racing with a grant therefore either
 * gets the permit back or leaves it with the caller, never both: no slot leaks
 * and no slot serves two requests.
 */
final class ConcurrencyLimiter {

    private static final Object GAVE_UP = new Object();
    private static final Object DELIVERED = new Object();

    private final int maxConcurrent;
    private final int maxQueue;
    private final Duration maxWait;
    private final Deque<Waiter> waiters = new ArrayDeque<>();
    private int inFlight;

    ConcurrencyLimiter(RouteThrottle policy) {
        this.maxConcurrent = policy.maxConcurrent();
        this.maxQueue = policy.maxQueue() == null ? 0 : policy.maxQueue();
        Duration wait = RoutePolicyRules.parseDuration(policy.maxWait());
        this.maxWait = wait == null ? Duration.ZERO : wait;
    }

    Mono<Permit> acquire() {
        return Mono.defer(() -> {
            Waiter waiter = new Waiter();
            Mono<Permit> grant;
            synchronized (this) {
                if (inFlight < maxConcurrent) {
                    inFlight++;
                    Permit permit = new Permit(this);
                    waiter.state.set(permit);
                    grant = Mono.just(permit);
                } else if (waiters.size() >= maxQueue || maxWait.isZero()) {
                    return Mono.error(new Rejected());
                } else {
                    waiters.addLast(waiter);
                    grant = waiter.sink.asMono().timeout(maxWait, Mono.defer(() -> giveUp(waiter)));
                }
            }
            return grant
                    // Handed over only if the caller has not given up in the meantime.
                    .<Permit>handle((permit, out) -> {
                        if (waiter.state.compareAndSet(permit, DELIVERED)) {
                            out.next(permit);
                        }
                    })
                    .doOnCancel(() -> abandon(waiter));
        });
    }

    synchronized int inFlight() {
        return inFlight;
    }

    synchronized int waiting() {
        return waiters.size();
    }

    // The wait ran out: refused, unless a permit was granted at the same instant.
    private Mono<Permit> giveUp(Waiter waiter) {
        if (waiter.state.compareAndSet(null, GAVE_UP)) {
            leaveQueue(waiter);
            return Mono.error(new Rejected());
        }
        return waiter.state.get() instanceof Permit permit ? Mono.just(permit) : Mono.error(new Rejected());
    }

    // The caller went away: leave the queue, or take back a permit it never received.
    private void abandon(Waiter waiter) {
        if (waiter.state.compareAndSet(null, GAVE_UP)) {
            leaveQueue(waiter);
            return;
        }
        if (waiter.state.get() instanceof Permit permit && waiter.state.compareAndSet(permit, GAVE_UP)) {
            permit.release();
        }
    }

    private synchronized void leaveQueue(Waiter waiter) {
        waiters.remove(waiter);
    }

    private void release() {
        Waiter next;
        Permit handedOver;
        synchronized (this) {
            while (true) {
                next = waiters.pollFirst();
                if (next == null) {
                    inFlight--;
                    return;
                }
                handedOver = new Permit(this);
                // The slot moves to the waiter (inFlight stays the same), unless it gave up.
                if (next.state.compareAndSet(null, handedOver)) {
                    break;
                }
            }
        }
        next.sink.tryEmitValue(handedOver);
    }

    /** One slot; releasing it twice is harmless. */
    static final class Permit {
        private final ConcurrencyLimiter limiter;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(ConcurrencyLimiter limiter) {
            this.limiter = limiter;
        }

        void release() {
            if (released.compareAndSet(false, true)) {
                limiter.release();
            }
        }

        boolean released() {
            return released.get();
        }
    }

    /** No slot, and the queue was full or the wait ran out. */
    static final class Rejected extends RuntimeException {
        Rejected() {
            super("concurrency limit reached", null, false, false);
        }
    }

    /** {@code state}: null while waiting, the permit once granted, then DELIVERED or GAVE_UP. */
    private static final class Waiter {
        final Sinks.One<Permit> sink = Sinks.one();
        final AtomicReference<Object> state = new AtomicReference<>();
    }
}
