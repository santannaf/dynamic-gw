package com.example.gateway.security.keycloak;

import com.example.gateway.security.session.SessionCookieCodec;
import com.example.gateway.security.session.SessionCookies;
import org.springframework.security.oauth2.client.web.server.ServerAuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Keeps the OIDC login state ({@code state}, {@code nonce}, PKCE verifier)
 * in a short-lived encrypted cookie instead of a WebSession, so the callback
 * can land on any replica. The cookie is scoped to the callback path.
 */
public class CookieAuthorizationRequestRepository implements ServerAuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    static final String CALLBACK_PATH = "/_gateway/login/oauth2/code";
    private static final Duration MAX_AGE = Duration.ofMinutes(5);

    private final SessionCookieCodec codec;
    private final SessionCookies cookies;
    private final String cookieName;

    public CookieAuthorizationRequestRepository(SessionCookieCodec codec, SessionCookies cookies, String cookieName) {
        this.codec = codec;
        this.cookies = cookies;
        this.cookieName = cookieName;
    }

    @Override
    public Mono<OAuth2AuthorizationRequest> loadAuthorizationRequest(ServerWebExchange exchange) {
        return Mono.justOrEmpty(read(exchange));
    }

    @Override
    public Mono<Void> saveAuthorizationRequest(OAuth2AuthorizationRequest request, ServerWebExchange exchange) {
        if (request == null) {
            cookies.expire(exchange, cookieName, CALLBACK_PATH);
            return Mono.empty();
        }
        ObjectNode payload = SessionCookieCodec.newPayload()
                .put("v", 1)
                .put("au", request.getAuthorizationUri())
                .put("cid", request.getClientId())
                .put("ru", request.getRedirectUri())
                .put("st", request.getState())
                .put("ar", request.getAuthorizationRequestUri());
        request.getScopes().forEach(payload.putArray("sc")::add);
        ObjectNode additional = payload.putObject("ap");
        request.getAdditionalParameters().forEach((k, v) -> additional.put(k, String.valueOf(v)));
        ObjectNode attributes = payload.putObject("at");
        request.getAttributes().forEach((k, v) -> attributes.put(k, String.valueOf(v)));
        cookies.write(exchange, cookieName, codec.encode(payload), CALLBACK_PATH, MAX_AGE);
        return Mono.empty();
    }

    @Override
    public Mono<OAuth2AuthorizationRequest> removeAuthorizationRequest(ServerWebExchange exchange) {
        Optional<OAuth2AuthorizationRequest> request = read(exchange);
        cookies.expire(exchange, cookieName, CALLBACK_PATH);
        return Mono.justOrEmpty(request);
    }

    private Optional<OAuth2AuthorizationRequest> read(ServerWebExchange exchange) {
        return cookies.read(exchange, cookieName)
                .flatMap(codec::decode)
                .map(CookieAuthorizationRequestRepository::toRequest);
    }

    private static OAuth2AuthorizationRequest toRequest(ObjectNode payload) {
        Set<String> scopes = new LinkedHashSet<>();
        JsonNode sc = payload.get("sc");
        if (sc != null) {
            sc.forEach(node -> scopes.add(node.asString()));
        }
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(payload.get("au").asString())
                .clientId(payload.get("cid").asString())
                .redirectUri(payload.get("ru").asString())
                .state(payload.get("st").asString())
                .scopes(scopes)
                .additionalParameters(strings(payload.get("ap")))
                .attributes(attributes -> attributes.putAll(strings(payload.get("at"))))
                .authorizationRequestUri(payload.get("ar").asString())
                .build();
    }

    private static Map<String, Object> strings(JsonNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (node != null) {
            node.properties().forEach(e -> map.put(e.getKey(), e.getValue().asString()));
        }
        return map;
    }
}
