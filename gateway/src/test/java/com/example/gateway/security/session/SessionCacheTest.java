package com.example.gateway.security.session;

import com.example.gateway.security.support.TestSupport.MutableClock;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCacheTest {

    private final MutableClock clock = new MutableClock();

    @Test
    void entriesExpireAtTheirDeadline() {
        SessionCache<String> cache = new SessionCache<>(10, clock);
        cache.put("a", "alice", clock.instant().plus(Duration.ofMinutes(5)));

        assertThat(cache.getIfValid("a")).contains("alice");
        clock.advance(Duration.ofMinutes(5));
        assertThat(cache.getIfValid("a")).isEmpty();
    }

    @Test
    void concurrentLoadsForTheSameKeyHitTheProviderOnce() {
        SessionCache<String> cache = new SessionCache<>(10, clock);
        AtomicInteger loads = new AtomicInteger();

        List<String> results = Flux.range(0, 10)
                .flatMap(i -> cache.getOrLoad("sid", () -> Mono.delay(Duration.ofMillis(100))
                        .map(t -> {
                            loads.incrementAndGet();
                            return new SessionCache.Loaded<>("alice", clock.instant().plusSeconds(60));
                        }))
                        .subscribeOn(Schedulers.parallel()))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(results).hasSize(10).containsOnly("alice");
        assertThat(loads).hasValue(1);
        assertThat(cache.getIfValid("sid")).contains("alice");
    }

    @Test
    void emptyOrFailedLoadsCacheNothing() {
        SessionCache<String> cache = new SessionCache<>(10, clock);

        assertThat(cache.getOrLoad("a", Mono::empty).blockOptional()).isEmpty();
        assertThat(cache.getOrLoad("b", () -> Mono.error(new SessionRejectedException("no")))
                .onErrorResume(e -> Mono.empty()).blockOptional()).isEmpty();
        assertThat(cache.size()).isZero();
    }

    @Test
    void overflowDropsExpiredThenSoonestToExpire() {
        SessionCache<String> cache = new SessionCache<>(2, clock);
        cache.put("expired", "x", clock.instant().minusSeconds(1));
        cache.put("late", "y", clock.instant().plusSeconds(600));
        cache.put("new", "z", clock.instant().plusSeconds(300));

        assertThat(cache.getIfValid("late")).contains("y");
        assertThat(cache.getIfValid("new")).contains("z");

        cache.put("newest", "w", clock.instant().plusSeconds(900));
        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.getIfValid("new")).isEmpty();
        assertThat(cache.getIfValid("newest")).contains("w");
    }
}
