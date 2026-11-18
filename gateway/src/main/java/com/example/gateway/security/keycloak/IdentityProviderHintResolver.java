package com.example.gateway.security.keycloak;

import com.example.shared.routes.RoutePolicyRules;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * A login started with {@code ?idp=<alias>} goes straight to that Keycloak
 * identity provider ({@code kc_idp_hint}) and asks Keycloak to authenticate
 * again ({@code prompt=login}): without it, a session Keycloak still holds from
 * a password login would answer the hint without reaching the provider.
 */
final class IdentityProviderHintResolver implements ServerOAuth2AuthorizationRequestResolver {

    static final String PARAMETER = "idp";

    private final ServerOAuth2AuthorizationRequestResolver delegate;

    IdentityProviderHintResolver(ServerOAuth2AuthorizationRequestResolver delegate) {
        this.delegate = delegate;
    }

    @Override
    public Mono<OAuth2AuthorizationRequest> resolve(ServerWebExchange exchange) {
        return delegate.resolve(exchange).map(request -> hinted(exchange, request));
    }

    @Override
    public Mono<OAuth2AuthorizationRequest> resolve(ServerWebExchange exchange, String clientRegistrationId) {
        return delegate.resolve(exchange, clientRegistrationId).map(request -> hinted(exchange, request));
    }

    private static OAuth2AuthorizationRequest hinted(ServerWebExchange exchange, OAuth2AuthorizationRequest request) {
        String identityProvider = exchange.getRequest().getQueryParams().getFirst(PARAMETER);
        if (!RoutePolicyRules.isIdentityProviderAlias(identityProvider)) {
            return request;
        }
        return OAuth2AuthorizationRequest.from(request)
                .additionalParameters(parameters -> {
                    parameters.put("kc_idp_hint", identityProvider);
                    parameters.put("prompt", "login");
                })
                .build();
    }
}
