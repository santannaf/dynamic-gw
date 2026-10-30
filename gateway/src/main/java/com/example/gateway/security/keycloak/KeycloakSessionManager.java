package com.example.gateway.security.keycloak;

import com.example.gateway.access.AccessAttributes;
import com.example.gateway.access.AccessProblem;
import com.example.gateway.access.GatewayIdentity;
import com.example.gateway.security.GatewaySecurityProperties;
import com.example.gateway.security.session.GatewaySessionManager;
import com.example.gateway.security.session.ProviderUnavailableException;
import com.example.gateway.security.session.SessionCache;
import com.example.gateway.security.session.SessionCookieCodec;
import com.example.gateway.security.session.SessionCookies;
import com.example.gateway.security.session.SessionRejectedException;
import com.example.gateway.security.token.TokenIdentities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Keycloak keeps the session; the gateway keeps only a cookie and a cache.
 *
 * The {@code GW_SESSION} cookie is a JWE with {@code {sid, sub, rt}} (rt = the
 * Keycloak refresh token). Each replica caches the identity and access token
 * per {@code sid} until the access token expires. On a cache miss, the replica
 * runs a refresh-token grant, which both fetches fresh tokens and proves the
 * Keycloak session is still alive, then re-issues the cookie with the new
 * refresh token.
 */
public class KeycloakSessionManager implements GatewaySessionManager {

    private static final Logger log = LoggerFactory.getLogger(KeycloakSessionManager.class);
    private static final String PROVIDER = "keycloak";
    private static final Duration EXPIRY_SKEW = Duration.ofSeconds(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SessionCookieCodec codec;
    private final SessionCookies cookies;
    private final ReactiveJwtDecoder accessTokenDecoder;
    private final KeycloakTokenClient tokenClient;
    private final TokenIdentities identities;
    private final SessionCache<CachedSession> cache;
    private final Clock clock;
    private final String cookieName;
    private final String endSessionUri;
    private final String clientId;
    private final String postLogoutRedirectUri;

    public KeycloakSessionManager(SessionCookieCodec codec,
                                  SessionCookies cookies,
                                  ReactiveJwtDecoder accessTokenDecoder,
                                  KeycloakTokenClient tokenClient,
                                  TokenIdentities identities,
                                  GatewaySecurityProperties properties,
                                  Clock clock) {
        this.codec = codec;
        this.cookies = cookies;
        this.accessTokenDecoder = accessTokenDecoder;
        this.tokenClient = tokenClient;
        this.identities = identities;
        this.clock = clock;
        this.cache = new SessionCache<>(properties.getSession().getCacheMaxEntries(), clock);
        this.cookieName = properties.getSession().getCookieName();
        this.endSessionUri = properties.getOidc().getIssuer() + "/protocol/openid-connect/logout";
        this.clientId = properties.getOidc().getClientId();
        this.postLogoutRedirectUri = properties.getPublicBaseUrl() + "/";
    }

    /** Called once the OIDC callback succeeded: caches the session and sets the cookie. */
    public Mono<GatewayIdentity> establish(ServerWebExchange exchange, String accessToken, String refreshToken,
                                           String idToken, String keycloakSessionId) {
        return accessTokenDecoder.decode(accessToken).map(jwt -> {
            String sid = firstNonBlank(keycloakSessionId, jwt.getClaimAsString("sid"), randomId());
            GatewayIdentity identity = identities.identity(jwt, sid, GatewayIdentity.AuthMethod.SESSION);
            cache.put(sid, new CachedSession(identity, idToken), cacheExpiry(jwt));
            writeCookie(exchange, sid, jwt.getSubject(), refreshToken);
            exchange.getAttributes().put(AccessAttributes.IDENTITY, identity);
            return identity;
        });
    }

    @Override
    public Mono<GatewayIdentity> resolve(ServerWebExchange exchange) {
        Optional<String> raw = cookies.read(exchange, cookieName);
        if (raw.isEmpty()) {
            return Mono.empty();
        }
        Optional<ObjectNode> payload = codec.decode(raw.get())
                .filter(p -> PROVIDER.equals(text(p, "p")) && text(p, "sid") != null);
        if (payload.isEmpty()) {
            cookies.expire(exchange, cookieName, "/");
            return Mono.empty();
        }
        String sid = text(payload.get(), "sid");
        return cache.getOrLoad(sid, () -> refresh(exchange, payload.get()))
                .map(CachedSession::identity)
                .onErrorResume(SessionRejectedException.class, e -> {
                    log.info("Keycloak session ended sid={} reason={}", sid, e.getMessage());
                    cache.evict(sid);
                    cookies.expire(exchange, cookieName, "/");
                    exchange.getAttributes().put(AccessAttributes.PROBLEM, AccessProblem.SESSION_EXPIRED);
                    return Mono.empty();
                })
                .onErrorResume(ProviderUnavailableException.class, e -> {
                    log.warn("Keycloak unavailable while revalidating sid={}: {}", sid, e.getMessage());
                    exchange.getAttributes().put(AccessAttributes.PROBLEM, AccessProblem.PROVIDER_UNAVAILABLE);
                    return Mono.empty();
                });
    }

    @Override
    public Mono<URI> logout(ServerWebExchange exchange) {
        Optional<ObjectNode> payload = cookies.read(exchange, cookieName).flatMap(codec::decode);
        cookies.expire(exchange, cookieName, "/");
        UriComponentsBuilder target = UriComponentsBuilder.fromUriString(endSessionUri)
                .queryParam("client_id", clientId)
                .queryParam("post_logout_redirect_uri", postLogoutRedirectUri);
        if (payload.isEmpty()) {
            return Mono.just(target.encode().build().toUri());
        }
        String sid = text(payload.get(), "sid");
        if (sid != null) {
            cache.getIfValid(sid)
                    .map(CachedSession::idToken)
                    .ifPresent(idToken -> target.queryParam("id_token_hint", idToken));
            cache.evict(sid);
        }
        return tokenClient.logout(text(payload.get(), "rt"))
                .then(Mono.fromSupplier(() -> target.encode().build().toUri()));
    }

    private Mono<SessionCache.Loaded<CachedSession>> refresh(ServerWebExchange exchange, ObjectNode payload) {
        String sid = text(payload, "sid");
        String oldRefreshToken = text(payload, "rt");
        return tokenClient.refresh(oldRefreshToken)
                .flatMap(tokens -> accessTokenDecoder.decode(tokens.accessToken())
                        .onErrorMap(JwtException.class,
                                e -> new SessionRejectedException("refreshed access token rejected: " + e.getMessage()))
                        .map(jwt -> {
                            GatewayIdentity identity = identities.identity(jwt, sid, GatewayIdentity.AuthMethod.SESSION);
                            String refreshToken = tokens.refreshToken() != null ? tokens.refreshToken() : oldRefreshToken;
                            writeCookie(exchange, sid, jwt.getSubject(), refreshToken);
                            return new SessionCache.Loaded<>(new CachedSession(identity, tokens.idToken()), cacheExpiry(jwt));
                        }));
    }

    private void writeCookie(ServerWebExchange exchange, String sid, String subject, String refreshToken) {
        ObjectNode payload = SessionCookieCodec.newPayload()
                .put("v", 1)
                .put("p", PROVIDER)
                .put("sid", sid)
                .put("sub", subject);
        if (refreshToken != null) {
            payload.put("rt", refreshToken);
        }
        cookies.write(exchange, cookieName, codec.encode(payload), "/");
    }

    private Instant cacheExpiry(Jwt jwt) {
        Instant expiresAt = jwt.getExpiresAt() == null ? clock.instant().plusSeconds(60) : jwt.getExpiresAt();
        Instant withSkew = expiresAt.minus(EXPIRY_SKEW);
        return withSkew.isAfter(clock.instant()) ? withSkew : expiresAt;
    }

    private static String text(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        return node == null || node.isNull() || node.asString().isBlank() ? null : node.asString();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String randomId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    record CachedSession(GatewayIdentity identity, String idToken) {

        @Override
        public String toString() {
            return "CachedSession[" + identity + "]";
        }
    }
}
