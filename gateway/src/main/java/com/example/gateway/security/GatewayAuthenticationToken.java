package com.example.gateway.security;

import com.example.gateway.access.GatewayIdentity;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Spring Security view of a {@link GatewayIdentity}, for both providers and
 * both credential types. Authorities keep the {@code group:}/{@code role:}
 * form so {@code hasAnyAuthority("role:gateway-admin")} matches what routes
 * declare.
 */
public class GatewayAuthenticationToken extends AbstractAuthenticationToken {

    private final GatewayIdentity identity;

    public GatewayAuthenticationToken(GatewayIdentity identity) {
        super(identity.authorities().stream().map(SimpleGrantedAuthority::new).toList());
        this.identity = identity;
        setAuthenticated(true);
    }

    public GatewayIdentity identity() {
        return identity;
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Object getPrincipal() {
        return identity;
    }

    @Override
    public String getName() {
        return identity.username() != null ? identity.username() : identity.subject();
    }
}
