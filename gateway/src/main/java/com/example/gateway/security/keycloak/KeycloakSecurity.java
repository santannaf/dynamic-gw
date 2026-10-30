package com.example.gateway.security.keycloak;

import com.example.gateway.access.UnauthenticatedHandler;
import com.example.gateway.security.BearerChallengeHandler;
import com.example.gateway.security.BrowserLoginHandler;
import com.example.gateway.security.GatewaySecurityProperties;
import com.example.gateway.security.IdentityChannel;
import com.example.gateway.security.LoginOutcomeHandlers;
import com.example.gateway.security.SecurityChains;
import com.example.gateway.security.session.BrowserSession;
import com.example.gateway.security.session.GatewaySessionManager;
import com.example.gateway.security.session.SessionSecurityContextRepository;
import com.example.gateway.security.token.AccessTokens;
import com.example.gateway.security.token.TokenIdentities;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.security.web.server.savedrequest.ServerRequestCache;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Clock;
import java.util.Set;

/**
 * Provider {@code keycloak}: OIDC login (authorization code + PKCE) with the
 * session held by Keycloak, plus bearer JWTs for APIs and service accounts.
 * The client registration is assembled from properties, without calling the
 * discovery endpoint, so the gateway starts while Keycloak is down: browsers
 * use the public issuer URL, the pods use the internal one.
 *
 */
public final class KeycloakSecurity implements IdentityChannel {

    private static final String AUTHORIZATION_PATH = "/_gateway/oauth2/authorization/{registrationId}";

    private final GatewaySecurityProperties properties;
    private final BrowserSession session;
    private final ReactiveClientRegistrationRepository registrations;
    private final ReactiveJwtDecoder accessTokenDecoder;
    private final TokenIdentities identities;
    private final KeycloakSessionManager sessionManager;
    private final BrowserLoginHandler browserLogin;

    private KeycloakSecurity(GatewaySecurityProperties properties, BrowserSession session, Clock clock) {
        this.properties = properties;
        this.session = session;
        this.registrations = clientRegistrations(properties);
        this.accessTokenDecoder = accessTokenDecoder(properties.getOidc());
        this.identities = TokenIdentities.from(properties.getAuthorities(), properties.getOidc().getClientId());
        this.sessionManager = new KeycloakSessionManager(session.codec(), session.cookies(), accessTokenDecoder,
                tokenClient(properties.getOidc()), identities, properties, clock);
        this.browserLogin = new BrowserLoginHandler(session, loginPath(), new BearerChallengeHandler());
    }

    public static KeycloakSecurity create(GatewaySecurityProperties properties, Clock clock) {
        BrowserSession session = BrowserSession.from(properties);
        GatewaySecurityProperties.Oidc oidc = properties.getOidc();
        SecurityChains.requireText(oidc.getIssuer(), "gateway.security.oidc.issuer");
        SecurityChains.requireText(oidc.getClientId(), "gateway.security.oidc.client-id");
        SecurityChains.requireText(oidc.getClientSecret(), "gateway.security.oidc.client-secret");
        SecurityChains.requireText(oidc.getAudience(), "gateway.security.oidc.audience");
        return new KeycloakSecurity(properties, session, clock);
    }

    @Override
    public GatewaySessionManager sessionManager() {
        return sessionManager;
    }

    @Override
    public Set<String> gatewayCookieNames() {
        return session.cookieNames();
    }

    @Override
    public UnauthenticatedHandler unauthenticatedHandler() {
        return browserLogin;
    }

    @Override
    public BrowserLoginHandler browserLogin() {
        return browserLogin;
    }

    @Override
    public ServerHttpSecurity configure(ServerHttpSecurity http) {
        ServerRequestCache requestCache = session.requestCache();
        SessionSecurityContextRepository contextRepository = new SessionSecurityContextRepository(sessionManager);
        DefaultServerOAuth2AuthorizationRequestResolver resolver = new DefaultServerOAuth2AuthorizationRequestResolver(
                registrations, new PathPatternParserServerWebExchangeMatcher(AUTHORIZATION_PATH));
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

        return SecurityChains.common(http, properties, SecurityChains.browserLoginEntryPoint(
                        loginPath(), requestCache, new BearerTokenServerAuthenticationEntryPoint()))
                .securityContextRepository(contextRepository)
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .oauth2Login(login -> login
                        .clientRegistrationRepository(registrations)
                        .authorizationRequestResolver(resolver)
                        .authorizationRequestRepository(new CookieAuthorizationRequestRepository(
                                session.codec(), session.cookies(), properties.getSession().getAuthzCookieName()))
                        .authenticationMatcher(new PathPatternParserServerWebExchangeMatcher(
                                CookieAuthorizationRequestRepository.CALLBACK_PATH + "/{registrationId}"))
                        .authorizedClientRepository(new KeycloakAuthorizedClientRepository(sessionManager))
                        .securityContextRepository(contextRepository)
                        .authenticationSuccessHandler(LoginOutcomeHandlers.success(session))
                        .authenticationFailureHandler(LoginOutcomeHandlers.oidcFailure())
                        .loginPage(loginPath()))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtDecoder(accessTokenDecoder)
                                .jwtAuthenticationConverter(identities.bearerAuthentication()))
                        .authenticationFailureHandler(AccessTokens.lenientBearerFailure()));
    }

    private String loginPath() {
        return "/_gateway/oauth2/authorization/" + properties.getOidc().getRegistrationId();
    }

    private static ReactiveClientRegistrationRepository clientRegistrations(GatewaySecurityProperties properties) {
        GatewaySecurityProperties.Oidc oidc = properties.getOidc();
        String publicIssuer = oidc.getIssuer();
        String internalIssuer = oidc.effectiveInternalIssuer();
        ClientRegistration registration = ClientRegistration.withRegistrationId(oidc.getRegistrationId())
                .clientId(oidc.getClientId())
                .clientSecret(oidc.getClientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(properties.getPublicBaseUrl() + CookieAuthorizationRequestRepository.CALLBACK_PATH
                        + "/" + oidc.getRegistrationId())
                .scope(oidc.getScopes())
                .authorizationUri(publicIssuer + "/protocol/openid-connect/auth")
                .tokenUri(internalIssuer + "/protocol/openid-connect/token")
                .jwkSetUri(internalIssuer + "/protocol/openid-connect/certs")
                .issuerUri(publicIssuer)
                .userNameAttributeName("preferred_username")
                .build();
        return new InMemoryReactiveClientRegistrationRepository(registration);
    }

    private static ReactiveJwtDecoder accessTokenDecoder(GatewaySecurityProperties.Oidc oidc) {
        return AccessTokens.decoder(oidc.effectiveInternalIssuer() + "/protocol/openid-connect/certs",
                oidc.getIssuer(), oidc.getAudience(), oidc.getRequiredTokenType());
    }

    private static KeycloakTokenClient tokenClient(GatewaySecurityProperties.Oidc oidc) {
        String internalIssuer = oidc.effectiveInternalIssuer();
        return new KeycloakTokenClient(WebClient.builder().build(),
                internalIssuer + "/protocol/openid-connect/token",
                internalIssuer + "/protocol/openid-connect/logout",
                oidc.getClientId(), oidc.getClientSecret());
    }
}
