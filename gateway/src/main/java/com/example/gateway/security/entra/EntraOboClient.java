package com.example.gateway.security.entra;

import com.example.gateway.access.UpstreamTokenException;
import com.example.gateway.security.token.IssuedToken;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

/**
 * Entra's On-Behalf-Of flow: from a token of the gateway's app (the
 * {@code assertion}, whose {@code aud} is that app), a token for the scopes of
 * the backend, still on behalf of the same user.
 */
public class EntraOboClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String JWT_BEARER = "urn:ietf:params:oauth:grant-type:jwt-bearer";

    private final WebClient webClient;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final Duration timeout;

    public EntraOboClient(WebClient webClient, String tokenUri, String clientId, String clientSecret, Duration timeout) {
        this.webClient = webClient;
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.timeout = timeout;
    }

    public Mono<IssuedToken> onBehalfOf(String assertion, List<String> scopes) {
        return webClient.post()
                .uri(tokenUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromFormData("grant_type", JWT_BEARER)
                        .with("client_id", clientId)
                        .with("client_secret", clientSecret)
                        .with("assertion", assertion)
                        .with("scope", String.join(" ", scopes))
                        .with("requested_token_use", "on_behalf_of"))
                .exchangeToMono(response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> {
                            int status = response.statusCode().value();
                            if (status == 200) {
                                return Mono.just(parse(body));
                            }
                            if (status >= 400 && status < 500) {
                                return Mono.error(new UpstreamTokenException(UpstreamTokenException.Reason.REFUSED,
                                        "Entra refused the On-Behalf-Of for " + scopes + ": " + errorCode(body)));
                            }
                            return Mono.error(new UpstreamTokenException(UpstreamTokenException.Reason.UNAVAILABLE,
                                    "Entra answered the On-Behalf-Of with status " + status));
                        }))
                .timeout(timeout)
                .onErrorMap(e -> !(e instanceof UpstreamTokenException),
                        e -> new UpstreamTokenException(UpstreamTokenException.Reason.UNAVAILABLE,
                                "Entra unreachable for the On-Behalf-Of: " + e.getClass().getSimpleName(), e));
    }

    private static IssuedToken parse(String body) {
        JsonNode json = JSON.readTree(body);
        JsonNode accessToken = json.get("access_token");
        if (accessToken == null || accessToken.isNull()) {
            throw new UpstreamTokenException(UpstreamTokenException.Reason.REFUSED, "Entra returned no access_token");
        }
        JsonNode expiresIn = json.get("expires_in");
        return new IssuedToken(accessToken.asString(),
                Duration.ofSeconds(expiresIn == null || expiresIn.isNull() ? 0 : expiresIn.asLong()));
    }

    // Entra's error_description repeats the AADSTS code and the user's tenant details; the code is enough for the log.
    private static String errorCode(String body) {
        try {
            JsonNode error = JSON.readTree(body).get("error");
            return error == null || error.isNull() ? null : error.asString();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
