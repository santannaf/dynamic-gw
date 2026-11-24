package com.example.gateway.security;

import com.example.gateway.access.AccessAttributes;
import com.example.gateway.access.AccessCapabilities;
import com.example.gateway.access.AccessChannels;
import com.example.gateway.access.AccessPolicy;
import com.example.gateway.access.AccessSettings;
import com.example.gateway.access.GatewayIdentity;
import com.example.gateway.access.UnauthenticatedHandler;
import com.example.gateway.access.UpstreamTokens;
import com.example.gateway.security.keycloak.KeycloakSecurity;
import com.example.gateway.security.ldap.LdapSecurity;
import com.example.gateway.security.oauth.OAuthSecurity;
import com.example.gateway.security.session.BrowserSession;
import com.example.gateway.security.session.GatewaySessionManager;
import com.example.gateway.security.session.SessionSecurityContextRepository;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The security setup of this process, decided from {@code gateway.security.*}
 * at startup. One object instead of {@code @ConditionalOnProperty} beans: the
 * native image evaluates conditions at build time, and a single image has to
 * serve every mode (off and each {@link IdentityChannel}), the same way
 * {@code RouteConfigStoreConfiguration} picks the route store at runtime.
 * <p>
 * {@code additional-providers} puts a channel next to the main one, for the
 * routes with {@code access.channel}: the ldap channel next to keycloak.
 */
public final class ActiveSecurity implements DisposableBean {

    private static final Map<String, IdentityChannel.Factory> CHANNELS = Map.of(
            GatewaySecurityProperties.PROVIDER_KEYCLOAK, KeycloakSecurity::create,
            GatewaySecurityProperties.PROVIDER_LDAP, LdapSecurity::create,
            GatewaySecurityProperties.PROVIDER_OAUTH, (properties, clock) -> OAuthSecurity.create(properties));

    private final GatewaySecurityProperties properties;
    private final IdentityChannel channel;
    private final Map<String, IdentityChannel> additional;
    private final GatewaySessionManager sessions;

    private ActiveSecurity(GatewaySecurityProperties properties, IdentityChannel channel,
                           Map<String, IdentityChannel> additional) {
        this.properties = properties;
        this.channel = channel;
        this.additional = additional;
        this.sessions = sessions(properties, channel, additional);
    }

    public static ActiveSecurity from(GatewaySecurityProperties properties, Clock clock) {
        if (!properties.isEnabled()) {
            return new ActiveSecurity(properties, null, Map.of());
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
        if (properties.getAdditionalProviders().isEmpty()) {
            return new ActiveSecurity(properties, factory.create(properties, clock), Map.of());
        }
        return ldapNextToKeycloak(properties, clock);
    }

    private static ActiveSecurity ldapNextToKeycloak(GatewaySecurityProperties properties, Clock clock) {
        if (!GatewaySecurityProperties.PROVIDER_KEYCLOAK.equals(properties.getProvider())
                || !List.of(GatewaySecurityProperties.PROVIDER_LDAP).equals(properties.getAdditionalProviders())) {
            throw new IllegalStateException("gateway.security.additional-providers supports only [ldap], next to provider"
                    + " keycloak, but was " + properties.getAdditionalProviders() + " next to " + properties.getProvider());
        }
        // One browser session for both: a logout's revocation reaches the session of either channel.
        BrowserSession session = BrowserSession.from(properties, clock);
        return new ActiveSecurity(properties, KeycloakSecurity.create(properties, session, clock),
                Map.of(GatewaySecurityProperties.PROVIDER_LDAP, LdapSecurity.additional(properties, session, clock)));
    }

    private static GatewaySessionManager sessions(GatewaySecurityProperties properties, IdentityChannel channel,
                                                  Map<String, IdentityChannel> additional) {
        if (channel == null || additional.isEmpty()) {
            return channel == null ? null : channel.sessionManager();
        }
        return new AllChannelSessions(channel.sessionManager(), properties.getSession().getCookieName(),
                additional.values().stream().map(IdentityChannel::sessionManager).toList());
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    public boolean provider(String name) {
        return enabled() && name.equals(properties.getProvider());
    }

    /** Whether {@code name} is the main channel or one next to it. */
    public boolean hasChannel(String name) {
        return provider(name) || (enabled() && additional.containsKey(name));
    }

    public GatewaySecurityProperties properties() {
        return properties;
    }

    /**
     * Sessions of the active channels; {@code null} when security is off or the
     * channel has no session. With two channels, the logout ends both.
     */
    public GatewaySessionManager sessionManager() {
        return sessions;
    }

    public UnauthenticatedHandler unauthenticatedHandler() {
        return channel.unauthenticatedHandler();
    }

    /** Browser login of the main channel; {@code null} when security is off or the channel has no login. */
    public BrowserLoginHandler browserLogin() {
        return channel != null ? channel.browserLogin() : null;
    }

    /**
     * The caller on channel {@code name}, or on the main channel when it is
     * {@code null} or the main one. The main channel's caller comes from the
     * security chain; another channel's session is resolved here, once per request.
     */
    public Mono<GatewayIdentity> identity(ServerWebExchange exchange, String name) {
        IdentityChannel extra = name == null ? null : additional.get(name);
        if (extra == null) {
            return Mono.justOrEmpty(exchange.<GatewayIdentity>getAttribute(AccessAttributes.IDENTITY));
        }
        String resolvedKey = ActiveSecurity.class.getName() + ".identity." + name;
        Mono<GatewayIdentity> resolved = exchange.getAttribute(resolvedKey);
        if (resolved == null) {
            resolved = Mono.defer(() -> {
                        // What went wrong with the main channel's session says nothing about this one.
                        exchange.getAttributes().remove(AccessAttributes.PROBLEM);
                        return extra.sessionManager().resolve(exchange);
                    })
                    .cache();
            exchange.getAttributes().put(resolvedKey, resolved);
        }
        return resolved;
    }

    public AccessChannels accessChannels() {
        if (additional.isEmpty()) {
            return AccessChannels.single(channel.unauthenticatedHandler());
        }
        return new AccessChannels() {
            @Override
            public Mono<GatewayIdentity> identity(ServerWebExchange exchange, String name) {
                return ActiveSecurity.this.identity(exchange, name);
            }

            @Override
            public UnauthenticatedHandler unauthenticatedHandler(String name) {
                IdentityChannel extra = name == null ? null : additional.get(name);
                return (extra != null ? extra : channel).unauthenticatedHandler();
            }
        };
    }

    public SecurityWebFilterChain buildChain(ServerHttpSecurity http) {
        if (!enabled()) {
            return SecurityChains.permitAll(http);
        }
        ServerHttpSecurity chain = channel.configure(http);
        if (additional.isEmpty()) {
            return chain.build();
        }
        for (IdentityChannel extra : additional.values()) {
            chain = extra.configureAdditional(chain);
        }
        return chain
                .securityContextRepository(new OperatorSecurityContextRepository(
                        new SessionSecurityContextRepository(channel.sessionManager()),
                        exchange -> Flux.fromIterable(additional.keySet())
                                .concatMap(name -> identity(exchange, name))
                                .next()))
                .build();
    }

    public AccessSettings accessSettings() {
        Set<String> cookies = new HashSet<>();
        if (channel == null) {
            // Off, the gateway sets no cookie, but a browser may still carry one from when it was on.
            cookies.addAll(BrowserSession.cookieNames(properties.getSession()));
            cookies.add(LdapSecurity.additionalCookieName(properties));
        } else {
            cookies.addAll(channel.gatewayCookieNames());
            additional.values().forEach(extra -> cookies.addAll(extra.gatewayCookieNames()));
        }
        return new AccessSettings(
                AccessPolicy.Mode.parse(properties.getDefaultAccess()),
                properties.getIdentityHeaders().getPrefix(),
                properties.getIdentityHeaders().getMaxLength(),
                cookies);
    }

    public UpstreamTokens upstreamTokens() {
        return channel == null ? UpstreamTokens.NONE : channel.upstreamTokens();
    }

    public AccessCapabilities accessCapabilities() {
        if (!enabled()) {
            return AccessCapabilities.disabled();
        }
        Map<String, Set<AccessCapabilities.Feature>> features = new HashMap<>();
        features.put(properties.getProvider(), channel.accessFeatures());
        additional.forEach((name, extra) -> features.put(name, extra.accessFeatures()));
        return new AccessCapabilities(true, properties.getProvider(), features);
    }

    @Override
    public void destroy() {
        if (channel != null) {
            channel.close();
        }
        additional.values().forEach(IdentityChannel::close);
    }
}
