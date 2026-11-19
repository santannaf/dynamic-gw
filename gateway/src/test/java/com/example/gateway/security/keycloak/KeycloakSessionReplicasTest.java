package com.example.gateway.security.keycloak;

import com.example.gateway.access.GatewayIdentity;
import com.example.gateway.security.GatewaySecurityProperties;
import com.example.gateway.security.support.FakeKeycloak;
import com.example.gateway.security.support.TestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two replicas: separate caches, the same session keys. The browser's login
 * goes to one, its next request to the other.
 */
class KeycloakSessionReplicasTest {

    static final FakeKeycloak keycloak = new FakeKeycloak();

    @AfterAll
    static void stop() {
        keycloak.close();
    }

    @Test
    void aNewLoginThatKeepsTheKeycloakSessionReachesTheOtherReplica() throws Exception {
        FakeKeycloak.User alice = FakeKeycloak.User.of("alice", List.of("tools-httpbin"), List.of());
        KeycloakSessionManager callbackReplica = replica();
        KeycloakSessionManager otherReplica = replica();

        HttpCookie passwordSession = login(callbackReplica, alice);
        assertThat(resolve(otherReplica, passwordSession).identityProvider()).isNull();
        HttpCookie microsoftSession = login(callbackReplica, alice.joinedThrough("microsoft"));

        GatewayIdentity identity = resolve(otherReplica, microsoftSession);
        assertThat(identity.sessionId()).isEqualTo(alice.sid());
        assertThat(identity.identityProvider()).isEqualTo("microsoft");
    }

    @Test
    void theSameLoginComesFromTheCacheOnEveryRequest() throws Exception {
        FakeKeycloak.User alice = FakeKeycloak.User.of("alice", List.of("tools-httpbin"), List.of());
        KeycloakSessionManager callbackReplica = replica();
        KeycloakSessionManager otherReplica = replica();
        HttpCookie session = login(callbackReplica, alice);
        keycloak.resetCounters();

        resolve(otherReplica, session);
        resolve(otherReplica, session);
        resolve(callbackReplica, session);

        assertThat(keycloak.refreshCalls()).isEqualTo(1);
    }

    private static KeycloakSessionManager replica() {
        GatewaySecurityProperties properties = new GatewaySecurityProperties();
        properties.setEnabled(true);
        properties.setProvider("keycloak");
        properties.setPublicBaseUrl("http://gateway.test");
        properties.getOidc().setIssuer(FakeKeycloak.PUBLIC_ISSUER);
        properties.getOidc().setInternalIssuer(keycloak.internalIssuer());
        properties.getOidc().setClientId(FakeKeycloak.CLIENT_ID);
        properties.getOidc().setClientSecret(FakeKeycloak.CLIENT_SECRET);
        GatewaySecurityProperties.Session.Key key = new GatewaySecurityProperties.Session.Key();
        key.setId("k1");
        key.setSecret(TestSupport.KEY_1);
        properties.getSession().setKeys(List.of(key));
        properties.getSession().setCookieSecure(false);
        return (KeycloakSessionManager) KeycloakSecurity.create(properties, Clock.systemUTC()).sessionManager();
    }

    private static HttpCookie login(KeycloakSessionManager replica, FakeKeycloak.User user)
            throws IOException, InterruptedException {
        keycloak.prepareLogin(user, "nonce");
        JsonNode tokens = codeExchange();
        MockServerWebExchange callback = MockServerWebExchange.from(
                MockServerHttpRequest.get("/_gateway/login/oauth2/code/keycloak"));
        replica.establish(callback, tokens.get("access_token").asString(), tokens.get("refresh_token").asString(),
                tokens.get("id_token").asString(), user.sid()).block();
        return new HttpCookie("GW_SESSION", callback.getResponse().getCookies().getFirst("GW_SESSION").getValue());
    }

    private static GatewayIdentity resolve(KeycloakSessionManager replica, HttpCookie session) {
        return replica.resolve(MockServerWebExchange.from(MockServerHttpRequest.get("/idp/headers").cookie(session)))
                .block();
    }

    private static JsonNode codeExchange() throws IOException, InterruptedException {
        String form = "grant_type=authorization_code&code=abc&redirect_uri=http%3A%2F%2Fgateway.test%2Fcallback"
                + "&client_id=" + FakeKeycloak.CLIENT_ID + "&client_secret=" + FakeKeycloak.CLIENT_SECRET;
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                            URI.create(keycloak.internalIssuer() + "/protocol/openid-connect/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build(), HttpResponse.BodyHandlers.ofString());
            return JsonMapper.builder().build().readTree(response.body());
        }
    }
}
