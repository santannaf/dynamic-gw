package com.example.gateway.security.token;

import com.example.gateway.access.GatewayIdentity;
import com.example.gateway.security.GatewayAuthenticationToken;
import com.example.gateway.security.GatewaySecurityProperties;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;

/** Builds a {@link GatewayIdentity} from a validated JWT access token. */
public class TokenIdentities {

    private final ClaimAuthorities authorities;

    public TokenIdentities(ClaimAuthorities authorities) {
        this.authorities = authorities;
    }

    public static TokenIdentities from(GatewaySecurityProperties.Authorities authorities, String clientId) {
        return new TokenIdentities(new ClaimAuthorities(authorities.getGroupClaims(), authorities.getRoleClaims(),
                authorities.getClaims(), clientId));
    }

    public GatewayIdentity identity(Jwt accessToken, String sessionId, GatewayIdentity.AuthMethod method) {
        return new GatewayIdentity(
                accessToken.getSubject(),
                accessToken.getClaimAsString("preferred_username"),
                accessToken.getClaimAsString("email"),
                sessionId,
                clientId(accessToken),
                authorities.from(accessToken.getClaims()),
                method,
                accessToken.getTokenValue(),
                accessToken.getExpiresAt());
    }

    public Converter<Jwt, Mono<AbstractAuthenticationToken>> bearerAuthentication() {
        return jwt -> Mono.just(new GatewayAuthenticationToken(
                identity(jwt, jwt.getClaimAsString("sid"), GatewayIdentity.AuthMethod.BEARER)));
    }

    // Keycloak names the client in azp; RFC 9068 tokens carry client_id.
    private static String clientId(Jwt accessToken) {
        return accessToken.hasClaim("azp") ? accessToken.getClaimAsString("azp") : accessToken.getClaimAsString("client_id");
    }
}
