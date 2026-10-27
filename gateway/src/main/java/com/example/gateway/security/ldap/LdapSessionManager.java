package com.example.gateway.security.ldap;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Sessions for the {@code ldap} provider. The cookie carries
 * {@code {sid, sub, dn, u, exp}}: no password and no token. Each replica
 * caches the identity for {@code revalidate-after}; after that it looks the
 * user up again with the service account, so a disabled account or a removed
 * group takes effect within that interval. {@code exp} caps the session's
 * absolute lifetime.
 */
public class LdapSessionManager implements GatewaySessionManager {

    private static final Logger log = LoggerFactory.getLogger(LdapSessionManager.class);
    private static final String PROVIDER = "ldap";
    private static final URI AFTER_LOGOUT = URI.create("/_gateway/login?logout");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SessionCookieCodec codec;
    private final SessionCookies cookies;
    private final LdapDirectory directory;
    private final SessionCache<GatewayIdentity> cache;
    private final Clock clock;
    private final String cookieName;
    private final Duration maxAge;
    private final Duration revalidateAfter;

    public LdapSessionManager(SessionCookieCodec codec, SessionCookies cookies, LdapDirectory directory,
                              GatewaySecurityProperties properties, Clock clock) {
        this.codec = codec;
        this.cookies = cookies;
        this.directory = directory;
        this.clock = clock;
        this.cache = new SessionCache<>(properties.getSession().getCacheMaxEntries(), clock);
        this.cookieName = properties.getSession().getCookieName();
        this.maxAge = properties.getSession().getMaxAge();
        this.revalidateAfter = properties.getLdap().getRevalidateAfter();
    }

    public GatewayIdentity establish(ServerWebExchange exchange, LdapUser user) {
        String sid = randomId();
        Instant expiresAt = clock.instant().plus(maxAge);
        GatewayIdentity identity = identity(user, sid, expiresAt);
        cache.put(sid, identity, revalidationDeadline(expiresAt));
        ObjectNode payload = SessionCookieCodec.newPayload()
                .put("v", 1)
                .put("p", PROVIDER)
                .put("sid", sid)
                .put("sub", user.subject())
                .put("dn", user.dn())
                .put("u", user.username())
                .put("exp", expiresAt.getEpochSecond());
        cookies.write(exchange, cookieName, codec.encode(payload), "/");
        exchange.getAttributes().put(AccessAttributes.IDENTITY, identity);
        return identity;
    }

    @Override
    public Mono<GatewayIdentity> resolve(ServerWebExchange exchange) {
        Optional<String> raw = cookies.read(exchange, cookieName);
        if (raw.isEmpty()) {
            return Mono.empty();
        }
        Optional<ObjectNode> payload = codec.decode(raw.get())
                .filter(p -> PROVIDER.equals(text(p, "p")) && text(p, "sid") != null && text(p, "dn") != null);
        if (payload.isEmpty()) {
            cookies.expire(exchange, cookieName, "/");
            return Mono.empty();
        }
        String sid = text(payload.get(), "sid");
        String dn = text(payload.get(), "dn");
        Instant expiresAt = Instant.ofEpochSecond(payload.get().path("exp").asLong(0));
        if (!expiresAt.isAfter(clock.instant())) {
            cache.evict(sid);
            cookies.expire(exchange, cookieName, "/");
            exchange.getAttributes().put(AccessAttributes.PROBLEM, AccessProblem.SESSION_EXPIRED);
            return Mono.empty();
        }
        return cache.getOrLoad(sid, () -> revalidate(dn, sid, expiresAt))
                .onErrorResume(SessionRejectedException.class, e -> {
                    log.info("LDAP session ended sid={} reason={}", sid, e.getMessage());
                    cache.evict(sid);
                    cookies.expire(exchange, cookieName, "/");
                    exchange.getAttributes().put(AccessAttributes.PROBLEM, AccessProblem.SESSION_EXPIRED);
                    return Mono.empty();
                })
                .onErrorResume(ProviderUnavailableException.class, e -> {
                    log.warn("Directory unavailable while revalidating sid={}: {}", sid, e.getMessage());
                    exchange.getAttributes().put(AccessAttributes.PROBLEM, AccessProblem.PROVIDER_UNAVAILABLE);
                    return Mono.empty();
                });
    }

    @Override
    public Mono<URI> logout(ServerWebExchange exchange) {
        cookies.read(exchange, cookieName)
                .flatMap(codec::decode)
                .map(p -> text(p, "sid"))
                .ifPresent(cache::evict);
        cookies.expire(exchange, cookieName, "/");
        return Mono.just(AFTER_LOGOUT);
    }

    private Mono<SessionCache.Loaded<GatewayIdentity>> revalidate(String dn, String sid, Instant expiresAt) {
        return Mono.fromCallable(() -> directory.lookup(dn))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(user -> user
                        .map(u -> Mono.just(new SessionCache.Loaded<>(identity(u, sid, expiresAt), revalidationDeadline(expiresAt))))
                        .orElseGet(() -> Mono.error(new SessionRejectedException("user no longer exists or is disabled"))));
    }

    private Instant revalidationDeadline(Instant expiresAt) {
        Instant next = clock.instant().plus(revalidateAfter);
        return next.isBefore(expiresAt) ? next : expiresAt;
    }

    private static GatewayIdentity identity(LdapUser user, String sid, Instant expiresAt) {
        return new GatewayIdentity(
                user.subject(),
                user.username(),
                user.email(),
                sid,
                null,
                user.groups().stream().map(g -> GatewayIdentity.GROUP_PREFIX + g).collect(Collectors.toSet()),
                GatewayIdentity.AuthMethod.SESSION,
                null,
                expiresAt);
    }

    private static String text(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        return node == null || node.isNull() || node.asString().isBlank() ? null : node.asString();
    }

    private static String randomId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
