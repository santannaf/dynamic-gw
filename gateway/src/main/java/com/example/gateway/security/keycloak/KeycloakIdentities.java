package com.example.gateway.security.keycloak;

import com.example.gateway.access.GatewayIdentity;
import org.springframework.security.oauth2.jwt.Jwt;

/** Builds a {@link GatewayIdentity} from a validated Keycloak access token. */
public class KeycloakIdentities {

    private final ClaimAuthorities authorities;

    public KeycloakIdentities(ClaimAuthorities authorities) {
        this.authorities = authorities;
    }

    public GatewayIdentity identity(Jwt accessToken, String sessionId, GatewayIdentity.AuthMethod method) {
        return new GatewayIdentity(
                accessToken.getSubject(),
                accessToken.getClaimAsString("preferred_username"),
                accessToken.getClaimAsString("email"),
                sessionId,
                accessToken.getClaimAsString("azp"),
                authorities.from(accessToken.getClaims()),
                method,
                accessToken.getTokenValue(),
                accessToken.getExpiresAt());
    }
}
