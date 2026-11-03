package com.example.gateway.security.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sessions ended by a logout on any replica of the gateway. Each replica keeps a cached
 * session until it revalidates it, and an LDAP session has no state outside the cookie, so
 * without this a copy of the cookie kept working on the other replicas after the logout.
 *
 * The replica that runs the logout refuses the session at once and sends a notice to every
 * address of {@code gateway.security.session.peers}, the headless Service that lists the
 * gateway pods. The notice is encrypted with the session key, so only a replica can write
 * one. A replica started after the logout does not get it: a Keycloak session is refused
 * there by Keycloak itself, an LDAP one only when it expires.
 */
public class SessionRevocations {

    public static final String NOTICE_PATH = "/internal/sessions/revoked";

    private static final Logger log = LoggerFactory.getLogger(SessionRevocations.class);
    private static final String NOTICE_TYPE = "revoke";
    private static final Duration NOTICE_TTL = Duration.ofMinutes(1);
    private static final Duration PEER_TIMEOUT = Duration.ofSeconds(2);

    private final SessionCookieCodec codec;
    private final String peerHost;
    private final int peerPort;
    private final int maxEntries;
    private final Clock clock;
    private final WebClient client;
    private final ConcurrentHashMap<String, Instant> revoked = new ConcurrentHashMap<>();

    public SessionRevocations(SessionCookieCodec codec, String peers, int maxEntries, Clock clock, WebClient client) {
        this.codec = codec;
        String target = peers == null ? "" : peers.strip();
        int colon = target.lastIndexOf(':');
        this.peerHost = colon > 0 ? target.substring(0, colon) : target;
        this.peerPort = colon > 0 ? Integer.parseInt(target.substring(colon + 1)) : 8080;
        this.maxEntries = maxEntries;
        this.clock = clock;
        this.client = client;
    }

    public boolean isRevoked(String sid) {
        Instant until = revoked.get(sid);
        if (until == null) {
            return false;
        }
        if (until.isAfter(clock.instant())) {
            return true;
        }
        revoked.remove(sid, until);
        return false;
    }

    /** Refuses the session here, then tells the other replicas; one out of reach does not hold up the logout. */
    public Mono<Void> revoke(String sid, Instant until) {
        remember(sid, until);
        if (peerHost.isEmpty()) {
            return Mono.empty();
        }
        ObjectNode notice = SessionCookieCodec.newPayload()
                .put("t", NOTICE_TYPE)
                .put("sid", sid)
                .put("until", until.getEpochSecond())
                .put("at", clock.instant().getEpochSecond());
        String body = codec.encode(notice);
        return Mono.fromCallable(() -> InetAddress.getAllByName(peerHost))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromArray)
                .flatMap(address -> tell(address, body))
                .then()
                .onErrorResume(UnknownHostException.class, e -> {
                    log.debug("No gateway replicas at {}: the logout stays on this one", peerHost);
                    return Mono.empty();
                });
    }

    /** A notice from another replica; false when it is not one: another key, stale or malformed. */
    public boolean accept(String body) {
        Optional<ObjectNode> notice = codec.decode(body).filter(p -> NOTICE_TYPE.equals(text(p, "t")));
        if (notice.isEmpty()) {
            return false;
        }
        String sid = text(notice.get(), "sid");
        long until = notice.get().path("until").asLong(0);
        Instant sent = Instant.ofEpochSecond(notice.get().path("at").asLong(0));
        if (sid == null || until <= 0 || Duration.between(sent, clock.instant()).abs().compareTo(NOTICE_TTL) > 0) {
            return false;
        }
        remember(sid, Instant.ofEpochSecond(until));
        return true;
    }

    private Mono<Void> tell(InetAddress address, String body) {
        String host = address instanceof Inet6Address ? "[" + address.getHostAddress() + "]" : address.getHostAddress();
        String url = "http://" + host + ":" + peerPort + NOTICE_PATH;
        return client.post().uri(url)
                .contentType(MediaType.TEXT_PLAIN)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .timeout(PEER_TIMEOUT)
                .then()
                .onErrorResume(e -> {
                    log.warn("Could not tell the replica at {} about a logout: {}", url, e.toString());
                    return Mono.empty();
                });
    }

    // Past the bound, a revocation still goes in: refusing a session that logged out matters more.
    private void remember(String sid, Instant until) {
        if (revoked.size() >= maxEntries) {
            Instant now = clock.instant();
            revoked.values().removeIf(expiry -> !expiry.isAfter(now));
        }
        revoked.put(sid, until);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
