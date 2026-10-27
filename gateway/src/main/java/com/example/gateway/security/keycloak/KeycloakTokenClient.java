package com.example.gateway.security.keycloak;

import com.example.gateway.security.session.ProviderUnavailableException;
import com.example.gateway.security.session.SessionRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

/**
 * Refresh-token grant and server-side logout against Keycloak's internal
 * endpoints. A refused refresh ({@code invalid_grant}) means the Keycloak
 * session is over; anything else that fails means Keycloak could not answer.
 */
public class KeycloakTokenClient {

    private static final Logger log = LoggerFactory.getLogger(KeycloakTokenClient.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final WebClient webClient;
    private final String tokenUri;
    private final String logoutUri;
    private final String clientId;
    private final String clientSecret;

    public KeycloakTokenClient(WebClient webClient, String tokenUri, String logoutUri,
                               String clientId, String clientSecret) {
        this.webClient = webClient;
        this.tokenUri = tokenUri;
        this.logoutUri = logoutUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public Mono<TokenResponse> refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return Mono.error(new SessionRejectedException("session has no refresh token"));
        }
        return webClient.post()
                .uri(tokenUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromFormData("grant_type", "refresh_token")
                        .with("refresh_token", refreshToken)
                        .with("client_id", clientId)
                        .with("client_secret", clientSecret))
                .exchangeToMono(response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> {
                            int status = response.statusCode().value();
                            if (status == 200) {
                                return Mono.just(parse(body));
                            }
                            String error = errorCode(body);
                            if (status == 400 && "invalid_grant".equals(error)) {
                                return Mono.error(new SessionRejectedException("refresh refused: invalid_grant"));
                            }
                            log.warn("Keycloak refresh failed status={} error={}", status, error);
                            return Mono.error(new ProviderUnavailableException("refresh failed with status " + status));
                        }))
                .timeout(TIMEOUT)
                .onErrorMap(e -> !(e instanceof SessionRejectedException) && !(e instanceof ProviderUnavailableException),
                        e -> new ProviderUnavailableException("Keycloak unreachable: " + e.getClass().getSimpleName(), e));
    }

    /** Best effort: ends the Keycloak session behind this refresh token. */
    public Mono<Void> logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return Mono.empty();
        }
        return webClient.post()
                .uri(logoutUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("refresh_token", refreshToken)
                        .with("client_id", clientId)
                        .with("client_secret", clientSecret))
                .exchangeToMono(response -> response.releaseBody())
                .timeout(TIMEOUT)
                .onErrorResume(e -> {
                    log.warn("Keycloak back-channel logout failed: {}", e.getClass().getSimpleName());
                    return Mono.empty();
                });
    }

    private static TokenResponse parse(String body) {
        JsonNode json = JSON.readTree(body);
        return new TokenResponse(text(json, "access_token"), text(json, "refresh_token"), text(json, "id_token"));
    }

    private static String errorCode(String body) {
        try {
            return text(JSON.readTree(body), "error");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode json, String field) {
        JsonNode node = json.get(field);
        return node == null || node.isNull() ? null : node.asString();
    }

    public record TokenResponse(String accessToken, String refreshToken, String idToken) {

        @Override
        public String toString() {
            return "TokenResponse[redacted]";
        }
    }
}
