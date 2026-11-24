package com.example.gateway.security.keycloak;

import com.example.gateway.access.AccessPolicy;
import com.example.gateway.access.GatewayIdentity;
import com.example.gateway.access.UpstreamTokenException;
import com.example.gateway.access.UpstreamTokens;
import com.example.gateway.security.entra.EntraOboClient;
import com.example.gateway.security.session.SessionCache;
import com.example.gateway.security.token.IssuedToken;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Tokens the Keycloak channel gets for the backends of routes that pick one,
 * cached per replica until shortly before they expire.
 */
final class KeycloakUpstreamTokens implements UpstreamTokens {

    private static final Duration EXPIRY_SKEW = Duration.ofSeconds(30);

    private final KeycloakTokenClient tokenClient;
    private final EntraOboClient obo;
    private final SessionCache<String> cache;
    private final Clock clock;

    /** {@code obo} is {@code null} when the gateway has no app in Entra. */
    KeycloakUpstreamTokens(KeycloakTokenClient tokenClient, EntraOboClient obo, int maxEntries, Clock clock) {
        this.tokenClient = tokenClient;
        this.obo = obo;
        this.cache = new SessionCache<>(maxEntries, clock);
        this.clock = clock;
    }

    @Override
    public Mono<String> tokenFor(GatewayIdentity identity, AccessPolicy policy) {
        if (identity.accessToken() == null) {
            return Mono.error(new UpstreamTokenException(UpstreamTokenException.Reason.REFUSED,
                    "the caller has no Keycloak token to exchange"));
        }
        String caller = callerKey(identity);
        if (policy.audience() != null) {
            return cache.getOrLoad(caller + "|aud:" + policy.audience(),
                    () -> tokenClient.exchangeForAudience(identity.accessToken(), policy.audience())
                            .map(token -> loaded(token, identity)));
        }
        if (obo == null) {
            return Mono.error(new UpstreamTokenException(UpstreamTokenException.Reason.REFUSED,
                    "this gateway has no app in Entra (gateway.security.obo) for access.obo"));
        }
        String identityProvider = policy.identityProvider();
        return cache.getOrLoad(caller + "|idp:" + identityProvider,
                        () -> tokenClient.exchangeForIdentityProvider(identity.accessToken(), identityProvider)
                                .map(token -> loaded(token, identity)))
                .flatMap(assertion -> cache.getOrLoad(caller + "|obo:" + String.join(" ", policy.oboScopes()),
                        () -> obo.onBehalfOf(assertion, policy.oboScopes()).map(token -> loaded(token, identity))));
    }

    private SessionCache.Loaded<String> loaded(IssuedToken token, GatewayIdentity identity) {
        return new SessionCache.Loaded<>(token.accessToken(), expiry(token.expiresIn(), identity));
    }

    /** Drops the tokens of a session that ended on this replica. */
    void forgetSession(String sessionId) {
        cache.evictWhere(key -> key.startsWith("sid:" + sessionId + "|"));
    }

    int size() {
        return cache.size();
    }

    // A session keeps its sid when its token A is refreshed; a bearer token stands on its own.
    private static String callerKey(GatewayIdentity identity) {
        return identity.method() == GatewayIdentity.AuthMethod.SESSION && identity.sessionId() != null
                ? "sid:" + identity.sessionId()
                : "jwt:" + sha256(identity.accessToken());
    }

    private Instant expiry(Duration expiresIn, GatewayIdentity identity) {
        Instant expiresAt = clock.instant().plus(expiresIn).minus(EXPIRY_SKEW);
        if (identity.method() == GatewayIdentity.AuthMethod.BEARER && identity.expiresAt() != null
                && identity.expiresAt().isBefore(expiresAt)) {
            return identity.expiresAt();
        }
        return expiresAt;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
