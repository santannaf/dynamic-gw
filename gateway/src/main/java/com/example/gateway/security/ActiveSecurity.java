package com.example.gateway.security;

import com.example.gateway.access.AccessPolicy;
import com.example.gateway.access.AccessSettings;
import com.example.gateway.access.UnauthenticatedHandler;
import com.example.gateway.security.keycloak.KeycloakSecurity;
import com.example.gateway.security.ldap.LdapSecurity;
import com.example.gateway.security.oauth.OAuthSecurity;
import com.example.gateway.security.session.GatewaySessionManager;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

import java.time.Clock;
import java.util.Map;
import java.util.TreeSet;

/**
 * The security setup of this process, decided from {@code gateway.security.*}
 * at startup. One object instead of {@code @ConditionalOnProperty} beans: the
 * native image evaluates conditions at build time, and a single image has to
 * serve every mode (off and each {@link IdentityChannel}), the same way
 * {@code RouteConfigStoreConfiguration} picks the route store at runtime.
 */
public final class ActiveSecurity implements DisposableBean {

    private static final Map<String, IdentityChannel.Factory> CHANNELS = Map.of(
            GatewaySecurityProperties.PROVIDER_KEYCLOAK, KeycloakSecurity::create,
            GatewaySecurityProperties.PROVIDER_LDAP, LdapSecurity::create,
            GatewaySecurityProperties.PROVIDER_OAUTH, (properties, clock) -> OAuthSecurity.create(properties));

    private final GatewaySecurityProperties properties;
    private final IdentityChannel channel;

    private ActiveSecurity(GatewaySecurityProperties properties, IdentityChannel channel) {
        this.properties = properties;
        this.channel = channel;
    }

    public static ActiveSecurity from(GatewaySecurityProperties properties, Clock clock) {
        if (!properties.isEnabled()) {
            return new ActiveSecurity(properties, null);
        }
        IdentityChannel.Factory factory = CHANNELS.get(properties.getProvider());
        if (factory == null) {
            throw new IllegalStateException("gateway.security.provider must be one of "
                    + new TreeSet<>(CHANNELS.keySet()) + " but was '" + properties.getProvider() + "'");
        }
        if (AccessPolicy.Mode.parse(properties.getDefaultAccess()) == null) {
            throw new IllegalStateException("gateway.security.default-access must be 'public' or 'authenticated' but was '"
                    + properties.getDefaultAccess() + "'");
        }
        return new ActiveSecurity(properties, factory.create(properties, clock));
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    public boolean provider(String name) {
        return enabled() && name.equals(properties.getProvider());
    }

    public GatewaySecurityProperties properties() {
        return properties;
    }

    /** Session manager of the active channel; {@code null} when security is off or the channel has no session. */
    public GatewaySessionManager sessionManager() {
        return channel != null ? channel.sessionManager() : null;
    }

    public UnauthenticatedHandler unauthenticatedHandler() {
        return channel.unauthenticatedHandler();
    }

    /** Browser login of the active channel; {@code null} when security is off or the channel has no login. */
    public BrowserLoginHandler browserLogin() {
        return channel != null ? channel.browserLogin() : null;
    }

    public SecurityWebFilterChain buildChain(ServerHttpSecurity http) {
        return enabled() ? channel.configure(http).build() : SecurityChains.permitAll(http);
    }

    public AccessSettings accessSettings() {
        return new AccessSettings(
                AccessPolicy.Mode.parse(properties.getDefaultAccess()),
                properties.getIdentityHeaders().getPrefix(),
                properties.getIdentityHeaders().getMaxLength(),
                channel.gatewayCookieNames());
    }

    @Override
    public void destroy() {
        if (channel != null) {
            channel.close();
        }
    }
}
