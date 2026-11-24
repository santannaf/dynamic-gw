package com.example.gateway.traffic;

import com.example.shared.routes.RouteThrottle;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ConcurrencyLimiterTest {

    @Test
    void permitsUpToTheLimitThenQueuesThenRefuses() {
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(new RouteThrottle(2, 1, "5s"));

        ConcurrencyLimiter.Permit first = limiter.acquire().block();
        ConcurrencyLimiter.Permit second = limiter.acquire().block();
        ConcurrentLinkedQueue<ConcurrencyLimiter.Permit> granted = new ConcurrentLinkedQueue<>();
        limiter.acquire().subscribe(granted::add);

        assertThat(limiter.waiting()).isEqualTo(1);
        StepVerifier.create(limiter.acquire()).expectError(ConcurrencyLimiter.Rejected.class).verify();

        first.release();
        assertThat(granted).hasSize(1);
        assertThat(limiter.inFlight()).as("the slot moved to the waiter").isEqualTo(2);
        second.release();
        granted.poll().release();
        assertThat(limiter.inFlight()).isZero();
    }

    @Test
    void waitingRunsOutIntoARefusal() {
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(new RouteThrottle(1, 5, "50ms"));
        ConcurrencyLimiter.Permit held = limiter.acquire().block();

        StepVerifier.create(limiter.acquire()).expectError(ConcurrencyLimiter.Rejected.class).verify(Duration.ofSeconds(2));

        assertThat(limiter.waiting()).isZero();
        held.release();
        assertThat(limiter.inFlight()).isZero();
    }

    @Test
    void withoutQueueOrWaitTheExcessIsRefusedAtOnce() {
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(new RouteThrottle(1, null, null));
        limiter.acquire().block();

        StepVerifier.create(limiter.acquire()).expectError(ConcurrencyLimiter.Rejected.class).verify(Duration.ofMillis(200));
    }

    @Test
    void aCallerThatLeavesTheQueueDoesNotKeepASlot() {
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(new RouteThrottle(1, 1, "5s"));
        ConcurrencyLimiter.Permit held = limiter.acquire().block();
        Disposable waiting = limiter.acquire().subscribe();
        assertThat(limiter.waiting()).isEqualTo(1);

        waiting.dispose();
        held.release();

        assertThat(limiter.waiting()).isZero();
        assertThat(limiter.inFlight()).isZero();
    }

    @Test
    void releasingTwiceGivesBackOneSlot() {
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(new RouteThrottle(2, null, null));
        ConcurrencyLimiter.Permit permit = limiter.acquire().block();
        limiter.acquire().block();

        permit.release();
        permit.release();

        assertThat(limiter.inFlight()).isEqualTo(1);
    }

    @RepeatedTest(30)
    void noSlotLeaksUnderConcurrencyCancellationAndTimeouts() {
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(new RouteThrottle(3, 10, "20ms"));
        AtomicInteger peak = new AtomicInteger();
        // Permits in the callers' hands: up when one is handed over, down right
        // before it is given back (giving it back may hand it to the next caller).
        AtomicInteger held = new AtomicInteger();
        Consumer<ConcurrencyLimiter.Permit> giveBack = permit -> {
            held.decrementAndGet();
            permit.release();
        };

        List<Integer> outcomes = Flux.range(0, 200)
                .flatMap(i -> Mono.usingWhen(
                                limiter.acquire().doOnNext(permit -> peak.accumulateAndGet(held.incrementAndGet(), Math::max)),
                                permit -> Mono.delay(Duration.ofMillis(i % 7)).thenReturn(1),
                                permit -> Mono.fromRunnable(() -> giveBack.accept(permit)),
                                (permit, error) -> Mono.fromRunnable(() -> giveBack.accept(permit)),
                                permit -> Mono.fromRunnable(() -> giveBack.accept(permit)))
                        .timeout(Duration.ofMillis(i % 11 == 0 ? 1 : 1_000), Mono.just(0))
                        .onErrorReturn(ConcurrencyLimiter.Rejected.class, -1)
                        .subscribeOn(Schedulers.parallel()), 64)
                .collectList()
                .block(Duration.ofSeconds(30));

        assertThat(outcomes).hasSize(200).contains(1);
        assertThat(peak.get()).isLessThanOrEqualTo(3);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(limiter.inFlight()).isZero();
            assertThat(limiter.waiting()).isZero();
        });
    }
}
