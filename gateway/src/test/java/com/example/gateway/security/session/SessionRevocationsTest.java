package com.example.gateway.security.session;

import com.example.gateway.security.support.TestSupport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SessionRevocationsTest {

    private final TestSupport.MutableClock clock = new TestSupport.MutableClock();
    private final SessionCookieCodec codec = TestSupport.codec("k1", TestSupport.KEY_1);

    private SessionRevocations revocations(String peers) {
        return new SessionRevocations(codec, peers, 100, clock, WebClient.create());
    }

    @Test
    void aRevokedSessionIsRefusedUntilItWouldHaveExpired() {
        SessionRevocations here = revocations("");

        here.revoke("s1", clock.instant().plusSeconds(60)).block();

        assertThat(here.isRevoked("s1")).isTrue();
        assertThat(here.isRevoked("s2")).isFalse();
        clock.advance(Duration.ofSeconds(61));
        assertThat(here.isRevoked("s1")).isFalse();
    }

    @Test
    void aNoticeCountsOnlyWithTheSessionKeyAndWhileItIsFresh() {
        SessionRevocations here = revocations("");

        assertThat(here.accept(TestSupport.revocationNotice(codec, "s1", clock.instant()))).isTrue();
        assertThat(here.accept(TestSupport.revocationNotice(TestSupport.codec("k2", TestSupport.KEY_2), "s2",
                clock.instant()))).as("another key").isFalse();
        assertThat(here.accept(TestSupport.revocationNotice(codec, "s3", clock.instant().minusSeconds(120))))
                .as("two minutes old").isFalse();
        assertThat(here.accept("not a notice")).isFalse();

        assertThat(here.isRevoked("s1")).isTrue();
        assertThat(here.isRevoked("s2")).isFalse();
        assertThat(here.isRevoked("s3")).isFalse();
    }

    @Test
    void theLogoutReachesTheOtherReplicas() throws Exception {
        SessionRevocations other = revocations("");
        HttpServer replica = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        replica.createContext(SessionRevocations.NOTICE_PATH, exchange -> {
            String notice = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(other.accept(notice) ? 204 : 400, -1);
            exchange.close();
        });
        replica.start();
        try {
            SessionRevocations here = revocations("127.0.0.1:" + replica.getAddress().getPort());

            here.revoke("s1", clock.instant().plusSeconds(60)).block(Duration.ofSeconds(10));

            assertThat(here.isRevoked("s1")).isTrue();
            assertThat(other.isRevoked("s1")).isTrue();
        } finally {
            replica.stop(0);
        }
    }

    @Test
    void withoutOtherReplicasTheLogoutStaysHere() {
        SessionRevocations here = revocations("no-such-gateway.invalid:8080");

        here.revoke("s1", clock.instant().plusSeconds(60)).block(Duration.ofSeconds(30));

        assertThat(here.isRevoked("s1")).isTrue();
    }
}
