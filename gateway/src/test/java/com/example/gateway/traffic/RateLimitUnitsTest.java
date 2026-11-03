package com.example.gateway.traffic;

import com.example.gateway.access.AccessAttributes;
import com.example.gateway.access.GatewayIdentity;
import com.example.shared.routes.RouteRateLimit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitUnitsTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    @Test
    void burstIsServedAtOnceThenOneRequestPerInterval() {
        RateLimiter limiter = new RateLimiter(new RouteRateLimit(4, "1s", null, null), 0);

        for (int i = 3; i >= 0; i--) {
            RateLimiter.Decision decision = limiter.tryAcquire(0);
            assertThat(decision.allowed()).isTrue();
            assertThat(decision.remaining()).isEqualTo(i);
        }
        RateLimiter.Decision refused = limiter.tryAcquire(0);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterNanos()).isEqualTo(SECOND / 4);
        assertThat(refused.resetNanos()).isEqualTo(SECOND);

        assertThat(limiter.tryAcquire(SECOND / 4).allowed()).isTrue();
        assertThat(limiter.tryAcquire(SECOND / 4).allowed()).isFalse();
        assertThat(limiter.idle(SECOND + SECOND / 4)).isTrue();
    }

    @Test
    void smallerBurstSpreadsTheSameRate() {
        RateLimiter limiter = new RateLimiter(new RouteRateLimit(10, "1s", 2, null), 0);

        assertThat(limiter.tryAcquire(0).allowed()).isTrue();
        assertThat(limiter.tryAcquire(0).allowed()).isTrue();
        assertThat(limiter.tryAcquire(0).allowed()).isFalse();
        assertThat(limiter.tryAcquire(SECOND / 10).allowed()).isTrue();
    }

    @Test
    void eachCallerHasItsOwnBudgetAndTheRouteWideOneIsShared() {
        AtomicLong clock = new AtomicLong();
        RateLimiters limiters = new RateLimiters(100, clock::get);
        RouteRateLimit policy = new RouteRateLimit(1, "1m", null, "ip");

        assertThat(limiters.tryAcquire("r", policy, "ip:1").orElseThrow().allowed()).isTrue();
        assertThat(limiters.tryAcquire("r", policy, "ip:1").orElseThrow().allowed()).isFalse();
        assertThat(limiters.tryAcquire("r", policy, "ip:2").orElseThrow().allowed()).isTrue();
        assertThat(limiters.tryAcquire("other", policy, "ip:1").orElseThrow().allowed()).isTrue();

        RouteRateLimit changed = new RouteRateLimit(2, "1m", null, "ip");
        assertThat(limiters.tryAcquire("r", changed, "ip:1").orElseThrow().allowed())
                .as("a new policy starts a new budget").isTrue();
    }

    @Test
    void fullBudgetsAreDroppedAndPastTheCapNewCallersAreNotLimitedPerCaller() {
        AtomicLong clock = new AtomicLong();
        RateLimiters limiters = new RateLimiters(2, clock::get);
        RouteRateLimit policy = new RouteRateLimit(1, "1s", null, "ip");

        limiters.tryAcquire("r", policy, "ip:1");
        limiters.tryAcquire("r", policy, "ip:2");
        assertThat(limiters.tryAcquire("r", policy, "ip:3")).as("cap reached, both budgets still in use").isEmpty();
        assertThat(limiters.tryAcquire("r", policy, null)).as("route-wide budgets never fail open").isPresent();

        clock.set(2 * SECOND);
        assertThat(limiters.tryAcquire("r", policy, "ip:3")).isPresent();
        assertThat(limiters.size()).as("every refilled budget, the route-wide one too, was dropped").isEqualTo(1);
    }

    @Test
    void ipKeyReadsTheEntryOfTheTrustedProxyNotTheOneTheCallerSent() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x")
                .header("X-Forwarded-For", "6.6.6.6, 10.0.0.7")
                .remoteAddress(new InetSocketAddress("10.42.0.9", 1234)));

        assertThat(CallerKeys.of("ip", exchange, 1)).isEqualTo("ip:10.0.0.7");
        assertThat(CallerKeys.of("ip", exchange, 2)).isEqualTo("ip:6.6.6.6");
        assertThat(CallerKeys.of("ip", exchange, 0)).isEqualTo("ip:10.42.0.9");
        assertThat(CallerKeys.of("route", exchange, 1)).isNull();
        assertThat(CallerKeys.of(null, exchange, 1)).isNull();
    }

    @Test
    void identityAndHeaderKeysFallBackToTheAddress() {
        MockServerWebExchange anonymous = MockServerWebExchange.from(MockServerHttpRequest.get("/x")
                .header("X-Forwarded-For", "10.0.0.7"));
        assertThat(CallerKeys.of("user", anonymous, 1)).isEqualTo("ip:10.0.0.7");
        assertThat(CallerKeys.of("client-id", anonymous, 1)).isEqualTo("ip:10.0.0.7");
        assertThat(CallerKeys.of("header:X-Api-Key", anonymous, 1)).isEqualTo("ip:10.0.0.7");

        MockServerWebExchange known = MockServerWebExchange.from(MockServerHttpRequest.get("/x")
                .header("X-Api-Key", "secret-key"));
        known.getAttributes().put(AccessAttributes.IDENTITY, new GatewayIdentity("sub-1", "alice", null, null,
                "tools-cli", Set.of(), GatewayIdentity.AuthMethod.BEARER, null, null));
        assertThat(CallerKeys.of("user", known, 1)).isEqualTo("user:sub-1");
        assertThat(CallerKeys.of("client-id", known, 1)).isEqualTo("client:tools-cli");
        assertThat(CallerKeys.of("header:X-Api-Key", known, 1))
                .startsWith("header:").doesNotContain("secret-key").hasSize("header:".length() + 64);
    }
}
