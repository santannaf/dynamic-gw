package com.example.gateway.security.oauth;

import com.example.gateway.access.UnauthenticatedHandler;
import com.example.gateway.security.BearerChallengeHandler;
import com.example.gateway.security.GatewaySecurityProperties;
import com.example.gateway.security.IdentityChannel;
import com.example.gateway.security.SecurityChains;
import com.example.gateway.security.token.AccessTokens;
import com.example.gateway.security.token.TokenIdentities;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

/**
 * Provider {@code oauth}: the example of a channel without a session, as in a
 * public API. The gateway only validates JWT access tokens, so there is no
 * login and no cookie, and a caller without a valid token gets a 401 even
 * from a browser. Nothing here is Keycloak's: any authorization server that
 * signs with RS256 fits.
 */
public final class OAuthSecurity implements IdentityChannel {

    private final GatewaySecurityProperties properties;
    private final ReactiveJwtDecoder accessTokenDecoder;
    private final TokenIdentities identities;

    private OAuthSecurity(GatewaySecurityProperties properties) {
        GatewaySecurityProperties.OAuth oauth = properties.getOauth();
        this.properties = properties;
        this.accessTokenDecoder = AccessTokens.decoder(oauth.getJwkSetUri(), oauth.getIssuer(), oauth.getAudience(),
                oauth.getRequiredTokenType());
        this.identities = TokenIdentities.from(properties.getAuthorities(), null);
    }

    public static OAuthSecurity create(GatewaySecurityProperties properties) {
        GatewaySecurityProperties.OAuth oauth = properties.getOauth();
        SecurityChains.requireText(oauth.getIssuer(), "gateway.security.oauth.issuer");
        SecurityChains.requireText(oauth.getJwkSetUri(), "gateway.security.oauth.jwk-set-uri");
        SecurityChains.requireText(oauth.getAudience(), "gateway.security.oauth.audience");
        return new OAuthSecurity(properties);
    }

    @Override
    public UnauthenticatedHandler unauthenticatedHandler() {
        return new BearerChallengeHandler();
    }

    @Override
    public ServerHttpSecurity configure(ServerHttpSecurity http) {
        return SecurityChains.common(http, properties, new BearerTokenServerAuthenticationEntryPoint())
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtDecoder(accessTokenDecoder)
                                .jwtAuthenticationConverter(identities.bearerAuthentication()))
                        .authenticationFailureHandler(AccessTokens.lenientBearerFailure()));
    }
}
