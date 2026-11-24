package com.example.gateway.security.keycloak;

import com.example.gateway.access.AccessPolicy;
import com.example.gateway.access.GatewayIdentity;
import com.example.gateway.access.UpstreamTokenException;
import com.example.gateway.security.support.FakeKeycloak;
import com.example.gateway.security.support.TestSupport.MutableClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeycloakUpstreamTokensTest {

    static final FakeKeycloak keycloak = new FakeKeycloak();
    static final FakeKeycloak.User ALICE = FakeKeycloak.User.of("alice", List.of("tools-httpbin"), List.of());

    private final MutableClock clock = new MutableClock();
    private KeycloakUpstreamTokens tokens;

    @BeforeEach
    void setUp() {
        keycloak.allowAudience("httpbin-api");
        keycloak.allowAudience("abc-api");
        keycloak.resetCounters();
        tokens = new KeycloakUpstreamTokens(new KeycloakTokenClient(WebClient.builder().build(),
                keycloak.internalIssuer() + "/protocol/openid-connect/token",
                keycloak.internalIssuer() + "/protocol/openid-connect/logout",
                FakeKeycloak.CLIENT_ID, FakeKeycloak.CLIENT_SECRET), null, 100, clock);
    }

    @AfterAll
    static void stop() {
        keycloak.close();
    }

    @Test
    void oneExchangePerSessionAndAudienceUntilItExpires() {
        GatewayIdentity session = session("s1");

        String first = tokens.tokenFor(session, audience("httpbin-api")).block();
        String again = tokens.tokenFor(session, audience("httpbin-api")).block();
        tokens.tokenFor(session, audience("abc-api")).block();

        assertThat(again).isEqualTo(first);
        assertThat(keycloak.exchangeCalls()).isEqualTo(2);
        clock.advance(Duration.ofMinutes(5));
        tokens.tokenFor(session, audience("httpbin-api")).block();
        assertThat(keycloak.exchangeCalls()).isEqualTo(3);
    }

    @Test
    void aSessionThatEndsLosesItsTokensAndOnlyItsTokens() {
        tokens.tokenFor(session("s1"), audience("httpbin-api")).block();
        tokens.tokenFor(session("s2"), audience("httpbin-api")).block();

        tokens.forgetSession("s1");

        assertThat(tokens.size()).isEqualTo(1);
        tokens.tokenFor(session("s1"), audience("httpbin-api")).block();
        assertThat(keycloak.exchangeCalls()).isEqualTo(3);
    }

    @Test
    void aBearerTokenNeverOutlivesTheCallersToken() {
        GatewayIdentity bearer = new GatewayIdentity(ALICE.subject(), "alice", null, null, FakeKeycloak.CLIENT_ID,
                Set.of(), GatewayIdentity.AuthMethod.BEARER, keycloak.accessToken(ALICE),
                clock.instant().plusSeconds(10), null);

        tokens.tokenFor(bearer, audience("httpbin-api")).block();
        clock.advance(Duration.ofSeconds(11));
        tokens.tokenFor(bearer, audience("httpbin-api")).block();

        assertThat(keycloak.exchangeCalls()).isEqualTo(2);
    }

    @Test
    void noTokenAOrAnOboRouteIsRefusedWithoutCallingKeycloak() {
        GatewayIdentity withoutToken = new GatewayIdentity("s", "u", null, "s1", null, Set.of(),
                GatewayIdentity.AuthMethod.SESSION, null, null, null);
        AccessPolicy obo = new AccessPolicy(AccessPolicy.Mode.AUTHENTICATED, List.of(), true, null, "microsoft", null,
                List.of("api://backend-b/pedidos.read"));

        assertThatThrownBy(() -> tokens.tokenFor(withoutToken, audience("httpbin-api")).block())
                .isInstanceOf(UpstreamTokenException.class);
        assertThatThrownBy(() -> tokens.tokenFor(session("s1"), obo).block())
                .isInstanceOf(UpstreamTokenException.class)
                .hasMessageContaining("gateway.security.obo");
        assertThat(keycloak.exchangeCalls()).isZero();
    }

    private GatewayIdentity session(String sid) {
        return new GatewayIdentity(ALICE.subject(), "alice", null, sid, FakeKeycloak.CLIENT_ID, Set.of(),
                GatewayIdentity.AuthMethod.SESSION, keycloak.accessToken(ALICE), clock.instant().plusSeconds(300), null);
    }

    private static AccessPolicy audience(String audience) {
        return new AccessPolicy(AccessPolicy.Mode.AUTHENTICATED, List.of(), true, null, null, audience, List.of());
    }
}
