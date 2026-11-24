package com.example.gateway.security.keycloak;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Hook into the OIDC callback: Spring Security saves the authorized client
 * right after a successful code exchange, which is exactly when the gateway
 * session (cookie + cache entry) must be created. The gateway never reads
 * authorized clients back; tokens live in {@link KeycloakSessionManager}.
 */
public class KeycloakAuthorizedClientRepository implements ServerOAuth2AuthorizedClientRepository {

    private final KeycloakSessionManager sessionManager;

    public KeycloakAuthorizedClientRepository(KeycloakSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public <T extends OAuth2AuthorizedClient> Mono<T> loadAuthorizedClient(String clientRegistrationId,
                                                                          Authentication principal,
                                                                          ServerWebExchange exchange) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal,
                                           ServerWebExchange exchange) {
        String idToken = null;
        String sid = null;
        if (principal != null && principal.getPrincipal() instanceof OidcUser user) {
            idToken = user.getIdToken().getTokenValue();
            sid = user.getIdToken().getClaimAsString("sid");
        }
        String refreshToken = client.getRefreshToken() == null ? null : client.getRefreshToken().getTokenValue();
        return sessionManager.establish(exchange, client.getAccessToken().getTokenValue(), refreshToken, idToken, sid)
                .then();
    }

    @Override
    public Mono<Void> removeAuthorizedClient(String clientRegistrationId, Authentication principal,
                                             ServerWebExchange exchange) {
        return Mono.empty();
    }
}
