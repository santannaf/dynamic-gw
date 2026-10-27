package com.example.gateway.security;

import com.example.gateway.access.AccessPolicy;
import com.example.gateway.access.AccessSettings;
import com.example.gateway.security.keycloak.KeycloakSecurity;
import com.example.gateway.security.ldap.LdapSecurity;
import com.example.gateway.security.session.GatewaySessionManager;
import com.example.gateway.security.session.SessionCookieCodec;
import com.example.gateway.security.session.SessionCookies;
import com.example.gateway.security.session.SessionSecurityContextRepository;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.savedrequest.CookieServerRequestCache;
import org.springframework.security.web.server.savedrequest.ServerRequestCache;

import java.time.Clock;
import java.util.Set;

/**
 * The security setup of this process, decided from {@code gateway.security.*}
 * at startup. One object instead of {@code @ConditionalOnProperty} beans: the
 * native image evaluates conditions at build time, and a single image has to
 * serve every mode (off, {@code keycloak}, {@code ldap}), the same way
 * {@code RouteConfigStoreConfiguration} picks the route store at runtime.
 */
public final class ActiveSecurity implements DisposableBean {

    /** Name of the cookie {@link CookieServerRequestCache} uses for the URL saved before login. */
    static final String SAVED_REQUEST_COOKIE = "REDIRECT_URI";

    private final GatewaySecurityProperties properties;
    private final ServerRequestCache requestCache;
    private final KeycloakSecurity keycloak;
    private final LdapSecurity ldap;

    private ActiveSecurity(GatewaySecurityProperties properties, ServerRequestCache requestCache,
                           KeycloakSecurity keycloak, LdapSecurity ldap) {
        this.properties = properties;
        this.requestCache = requestCache;
        this.keycloak = keycloak;
        this.ldap = ldap;
    }

    public static ActiveSecurity from(GatewaySecurityProperties properties, Clock clock) {
        if (!properties.isEnabled()) {
            return new ActiveSecurity(properties, null, null, null);
        }
        String provider = properties.getProvider();
        if (!GatewaySecurityProperties.PROVIDER_KEYCLOAK.equals(provider)
                && !GatewaySecurityProperties.PROVIDER_LDAP.equals(provider)) {
            throw new IllegalStateException("gateway.security.provider must be '"
                    + GatewaySecurityProperties.PROVIDER_KEYCLOAK + "' or '"
                    + GatewaySecurityProperties.PROVIDER_LDAP + "' but was '" + provider + "'");
        }
        if (AccessPolicy.Mode.parse(properties.getDefaultAccess()) == null) {
            throw new IllegalStateException("gateway.security.default-access must be 'public' or 'authenticated' but was '"
                    + properties.getDefaultAccess() + "'");
        }
        SecurityChains.requireText(properties.getPublicBaseUrl(), "gateway.security.public-base-url");

        SessionCookieCodec codec = new SessionCookieCodec(properties.getSession().getKeys());
        SessionCookies cookies = new SessionCookies(properties.getSession());
        CookieServerRequestCache requestCache = new CookieServerRequestCache();
        boolean secure = properties.getSession().isCookieSecure();
        requestCache.setCookieCustomizer(cookie -> cookie.secure(secure).sameSite("Lax"));

        if (GatewaySecurityProperties.PROVIDER_LDAP.equals(provider)) {
            return new ActiveSecurity(properties, requestCache, null, LdapSecurity.create(properties, codec, cookies, clock));
        }
        return new ActiveSecurity(properties, requestCache, KeycloakSecurity.create(properties, codec, cookies, clock), null);
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

    public ServerRequestCache requestCache() {
        return requestCache;
    }

    /** Session manager of the active provider; {@code null} when security is off. */
    public GatewaySessionManager sessionManager() {
        if (keycloak != null) {
            return keycloak.sessionManager();
        }
        return ldap != null ? ldap.sessionManager() : null;
    }

    public SecurityWebFilterChain buildChain(ServerHttpSecurity http) {
        if (!enabled()) {
            return SecurityChains.permitAll(http);
        }
        SessionSecurityContextRepository contextRepository = new SessionSecurityContextRepository(sessionManager());
        ServerHttpSecurity configured = keycloak != null
                ? keycloak.configure(http, contextRepository, requestCache)
                : ldap.configure(http, contextRepository, requestCache);
        return configured.build();
    }

    public AccessSettings accessSettings() {
        GatewaySecurityProperties.Session session = properties.getSession();
        return new AccessSettings(
                AccessPolicy.Mode.parse(properties.getDefaultAccess()),
                properties.getIdentityHeaders().getPrefix(),
                properties.getIdentityHeaders().getMaxLength(),
                Set.of(session.getCookieName(), session.getAuthzCookieName(), SAVED_REQUEST_COOKIE),
                keycloak != null);
    }

    @Override
    public void destroy() {
        if (ldap != null) {
            ldap.close();
        }
    }
}
