package com.example.gateway.security.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
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
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entra's token endpoint for the On-Behalf-Of grant, on 127.0.0.1. It takes an
 * assertion whose aud is the gateway's app and answers a token for the scope's
 * resource, on behalf of the assertion's user.
 */
public class FakeEntra implements AutoCloseable {

    public static final String TENANT = "fake-tenant";
    public static final String ISSUER = "https://login.microsoftonline.com/" + TENANT + "/v2.0";
    public static final String GATEWAY_APP = "11111111-2222-3333-4444-555555555555";
    public static final String GATEWAY_APP_SECRET = "gateway-app-secret";

    private final HttpServer server;
    private final RSAKey key;
    private final AtomicInteger oboCalls = new AtomicInteger();
    private volatile boolean down;
    private volatile boolean refusing;

    public FakeEntra() {
        try {
            key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/" + TENANT + "/oauth2/v2.0/token", this::token);
        server.start();
    }

    public String tokenUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + TENANT + "/oauth2/v2.0/token";
    }

    /** The token Entra issued to the gateway's app at the user's login: what Keycloak stores. */
    public String gatewayAppToken(String oid, String username, Duration lifetime) {
        Instant now = Instant.now();
        return sign(new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(GATEWAY_APP)
                .subject(oid)
                .claim("oid", oid)
                .claim("preferred_username", username)
                .claim("scp", "access_as_user")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(lifetime)))
                .build());
    }

    public void setDown(boolean down) {
        this.down = down;
    }

    public void setRefusing(boolean refusing) {
        this.refusing = refusing;
    }

    public int oboCalls() {
        return oboCalls.get();
    }

    public void reset() {
        down = false;
        refusing = false;
        oboCalls.set(0);
    }

    private void token(HttpExchange exchange) throws IOException {
        oboCalls.incrementAndGet();
        if (down) {
            respond(exchange, 503, "{\"error\":\"temporarily_unavailable\"}");
            return;
        }
        Map<String, String> form = form(exchange);
        if (!GATEWAY_APP.equals(form.get("client_id")) || !GATEWAY_APP_SECRET.equals(form.get("client_secret"))) {
            respond(exchange, 401, "{\"error\":\"invalid_client\"}");
            return;
        }
        if (!"urn:ietf:params:oauth:grant-type:jwt-bearer".equals(form.get("grant_type"))
                || !"on_behalf_of".equals(form.get("requested_token_use"))) {
            respond(exchange, 400, "{\"error\":\"unsupported_grant_type\"}");
            return;
        }
        if (refusing) {
            respond(exchange, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"AADSTS65001: consent required\"}");
            return;
        }
        JWTClaimsSet assertion;
        try {
            assertion = SignedJWT.parse(form.getOrDefault("assertion", "")).getJWTClaimsSet();
        } catch (ParseException e) {
            respond(exchange, 400, "{\"error\":\"invalid_request\"}");
            return;
        }
        if (!assertion.getAudience().contains(GATEWAY_APP)) {
            respond(exchange, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"AADSTS50013: wrong audience\"}");
            return;
        }
        String scope = form.getOrDefault("scope", "");
        int slash = scope.lastIndexOf('/');
        Instant now = Instant.now();
        String token = sign(new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(slash > 0 ? scope.substring(0, slash) : scope)
                .subject(assertion.getSubject())
                .claim("oid", assertion.getClaim("oid"))
                .claim("azp", GATEWAY_APP)
                .claim("scp", slash > 0 ? scope.substring(slash + 1) : scope)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofHours(1))))
                .build());
        respond(exchange, 200, "{\"token_type\":\"Bearer\",\"scope\":\"" + scope + "\",\"expires_in\":3599,"
                + "\"access_token\":\"" + token + "\"}");
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
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
