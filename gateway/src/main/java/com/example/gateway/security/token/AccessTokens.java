package com.example.gateway.security.token;

import com.example.gateway.access.AccessAttributes;
import com.example.gateway.access.AccessProblem;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Validation of JWT access tokens, shared by the channels that accept them. */
public final class AccessTokens {

    private AccessTokens() {
    }

    /** @param requiredTokenType expected {@code typ} claim; blank skips the check */
    public static ReactiveJwtDecoder decoder(String jwkSetUri, String issuer, String audience, String requiredTokenType) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withJwkSetUri(jwkSetUri)
                .webClient(WebClient.builder().build())
                .build();
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator(Duration.ofSeconds(30)));
        validators.add(new JwtIssuerValidator(issuer));
        validators.add(new JwtClaimValidator<>(JwtClaimNames.AUD, aud -> audienceContains(aud, audience)));
        if (requiredTokenType != null && !requiredTokenType.isBlank()) {
            validators.add(new JwtClaimValidator<>("typ", requiredTokenType::equals));
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    /**
     * An invalid bearer token does not end the request here: public routes may
     * receive tokens meant for the backend. The route filter decides, using
     * the {@link AccessProblem#INVALID_TOKEN} marker.
     */
    public static ServerAuthenticationFailureHandler lenientBearerFailure() {
        return (webFilterExchange, exception) -> {
            webFilterExchange.getExchange().getAttributes().put(AccessAttributes.PROBLEM, AccessProblem.INVALID_TOKEN);
            return webFilterExchange.getChain().filter(webFilterExchange.getExchange());
        };
    }

    private static boolean audienceContains(Object aud, String expected) {
        if (aud instanceof Collection<?> values) {
            return values.stream().anyMatch(v -> expected.equals(String.valueOf(v)));
        }
        return aud != null && expected.equals(aud.toString());
    }
}
