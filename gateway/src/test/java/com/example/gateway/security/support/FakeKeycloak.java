package com.example.gateway.security.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal Keycloak stand-in for tests: JWKS, token endpoint (authorization
 * code and refresh grants) and back-channel logout. Tokens are RS256 JWTs
 * signed with a key generated per instance, shaped like Keycloak's.
 */
public final class FakeKeycloak implements AutoCloseable {

    public static final String PUBLIC_ISSUER = "http://keycloak.test/realms/tools";
    public static final String CLIENT_ID = "tools-gateway";
    public static final String CLIENT_SECRET = "test-secret";

    private final HttpServer server;
    private final RSAKey key;
    private final Map<String, User> sessionsByRefreshToken = new ConcurrentHashMap<>();
    private final Set<String> endedSessions = ConcurrentHashMap.newKeySet();
    private final AtomicInteger refreshCalls = new AtomicInteger();
    private final AtomicInteger logoutCalls = new AtomicInteger();
    private volatile User nextLogin;
    private volatile String nextNonce;
    private volatile boolean down;
    private volatile Duration accessTokenLifetime = Duration.ofMinutes(5);

    public FakeKeycloak() {
        try {
            key = new RSAKeyGenerator(2048).keyID("test-key").generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/realms/tools/protocol/openid-connect/certs",
                exchange -> respond(exchange, 200, new JWKSet(key.toPublicJWK()).toString()));
        server.createContext("/realms/tools/protocol/openid-connect/token", this::token);
        server.createContext("/realms/tools/protocol/openid-connect/logout", exchange -> {
            Map<String, String> form = form(exchange);
            logoutCalls.incrementAndGet();
            User user = sessionsByRefreshToken.get(form.getOrDefault("refresh_token", ""));
            if (user != null) {
                endedSessions.add(user.sid());
            }
            respond(exchange, 204, "");
        });
        server.start();
    }

    public String internalIssuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/tools";
    }

    /** The user the next authorization code exchange will log in. */
    public void prepareLogin(User user, String nonce) {
        this.nextLogin = user;
        this.nextNonce = nonce;
    }

    public void endSession(String sid) {
        endedSessions.add(sid);
    }

    public void setDown(boolean down) {
        this.down = down;
    }

    public void setAccessTokenLifetime(Duration lifetime) {
        this.accessTokenLifetime = lifetime;
    }

    public int refreshCalls() {
        return refreshCalls.get();
    }

    public int logoutCalls() {
        return logoutCalls.get();
    }

    public void resetCounters() {
        refreshCalls.set(0);
        logoutCalls.set(0);
    }

    public String accessToken(User user, String audience, String typ, Duration lifetime) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(PUBLIC_ISSUER)
                .subject(user.subject())
                .audience(audience)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(lifetime)))
                .claim("typ", typ)
                .claim("azp", user.clientId())
                .claim("sid", user.sid())
                .claim("preferred_username", user.username())
                .claim("groups", user.groups())
                .claim("realm_access", Map.of("roles", user.roles()));
        if (user.email() != null) {
            claims.claim("email", user.email());
        }
        return sign(claims.build());
    }

    public String accessToken(User user) {
        return accessToken(user, CLIENT_ID, "Bearer", accessTokenLifetime);
    }

    private String idToken(User user, String nonce) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(PUBLIC_ISSUER)
                .subject(user.subject())
                .audience(CLIENT_ID)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(accessTokenLifetime)))
                .claim("typ", "ID")
                .claim("azp", CLIENT_ID)
                .claim("sid", user.sid())
                .claim("preferred_username", user.username());
        if (nonce != null) {
            claims.claim("nonce", nonce);
        }
        return sign(claims.build());
    }

    private void token(HttpExchange exchange) throws IOException {
        if (down) {
            respond(exchange, 503, "{\"error\":\"temporarily_unavailable\"}");
            return;
        }
        Map<String, String> form = form(exchange);
        if (!CLIENT_ID.equals(form.get("client_id")) || !CLIENT_SECRET.equals(form.get("client_secret"))) {
            respond(exchange, 401, "{\"error\":\"invalid_client\"}");
            return;
        }
        User user;
        String nonce = null;
        switch (form.getOrDefault("grant_type", "")) {
            case "authorization_code" -> {
                user = nextLogin;
                nonce = nextNonce;
            }
            case "refresh_token" -> {
                refreshCalls.incrementAndGet();
                user = sessionsByRefreshToken.get(form.getOrDefault("refresh_token", ""));
                if (user == null || endedSessions.contains(user.sid()) || user.current().disabled()) {
                    respond(exchange, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"Session not active\"}");
                    return;
                }
                user = user.current();
            }
            default -> {
                respond(exchange, 400, "{\"error\":\"unsupported_grant_type\"}");
                return;
            }
        }
        String refreshToken = "rt-" + UUID.randomUUID();
        sessionsByRefreshToken.put(refreshToken, user);
        String body = "{\"access_token\":\"" + accessToken(user) + "\",\"token_type\":\"Bearer\",\"expires_in\":"
                + accessTokenLifetime.toSeconds() + ",\"refresh_token\":\"" + refreshToken + "\",\"id_token\":\""
                + idToken(user, nonce) + "\",\"scope\":\"openid profile email\"}";
        respond(exchange, 200, body);
    }

    private String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> form(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> form = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return form;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (status != 204) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /**
     * A Keycloak user. {@code current()} lets a test change groups or disable
     * the account between logins and refreshes through {@link Directory}.
     */
    public record User(String subject, String username, String email, String sid, String clientId,
                       List<String> groups, List<String> roles, boolean disabled, Directory directory) {

        public static User of(String username, List<String> groups, List<String> roles) {
            Directory directory = new Directory();
            User user = new User(UUID.randomUUID().toString(), username, username + "@tools.local",
                    UUID.randomUUID().toString(), CLIENT_ID, groups, roles, false, directory);
            directory.state.put(user.subject(), user);
            return user;
        }

        public static User serviceAccount(String clientId, List<String> roles) {
            return new User(UUID.randomUUID().toString(), "service-account-" + clientId, null,
                    null, clientId, List.of(), roles, false, new Directory());
        }

        public User withGroups(List<String> newGroups) {
            User changed = new User(subject, username, email, sid, clientId, newGroups, roles, disabled, directory);
            directory.state.put(subject, changed);
            return changed;
        }

        public User disable() {
            User changed = new User(subject, username, email, sid, clientId, groups, roles, true, directory);
            directory.state.put(subject, changed);
            return changed;
        }

        User current() {
            return directory.state.getOrDefault(subject, this);
        }
    }

    public static final class Directory {
        private final Map<String, User> state = new ConcurrentHashMap<>();
    }
}
