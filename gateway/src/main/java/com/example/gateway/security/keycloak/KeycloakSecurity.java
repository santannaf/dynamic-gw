package com.example.gateway.security.keycloak;

import com.example.gateway.access.AccessAttributes;
import com.example.gateway.access.AccessProblem;
import com.example.gateway.access.GatewayIdentity;
import com.example.gateway.security.GatewayAuthenticationToken;
import com.example.gateway.security.GatewaySecurityProperties;
import com.example.gateway.security.LoginOutcomeHandlers;
import com.example.gateway.security.SecurityChains;
import com.example.gateway.security.session.GatewaySessionManager;
import com.example.gateway.security.session.SessionCookieCodec;
import com.example.gateway.security.session.SessionCookies;
import com.example.gateway.security.session.SessionSecurityContextRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler;
import org.springframework.security.web.server.savedrequest.ServerRequestCache;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Provider {@code keycloak}: OIDC login (authorization code + PKCE) with the
 * session held by Keycloak, plus bearer JWTs for APIs and service accounts.
 * The client registration is assembled from properties, without calling the
 * discovery endpoint, so the gateway starts while Keycloak is down: browsers
 * use the public issuer URL, the pods use the internal one.
 *
 * Plain factory rather than a {@code @Configuration}: the provider is picked
 * at startup, and the native image evaluates {@code @Conditional} at build time.
 */
public final class KeycloakSecurity {

    private static final String AUTHORIZATION_PATH = "/_gateway/oauth2/authorization/{registrationId}";

    private final GatewaySecurityProperties properties;
    private final SessionCookieCodec codec;
    private final SessionCookies cookies;
    private final ReactiveClientRegistrationRepository registrations;
    private final ReactiveJwtDecoder accessTokenDecoder;
    private final KeycloakIdentities identities;
    private final KeycloakSessionManager sessionManager;

    private KeycloakSecurity(GatewaySecurityProperties properties, SessionCookieCodec codec, SessionCookies cookies,
                             Clock clock) {
        this.properties = properties;
        this.codec = codec;
        this.cookies = cookies;
        this.registrations = clientRegistrations(properties);
        this.accessTokenDecoder = accessTokenDecoder(properties.getOidc());
        GatewaySecurityProperties.Authorities authorities = properties.getAuthorities();
        this.identities = new KeycloakIdentities(new ClaimAuthorities(
                authorities.getGroupClaims(), authorities.getRoleClaims(), properties.getOidc().getClientId()));
        this.sessionManager = new KeycloakSessionManager(codec, cookies, accessTokenDecoder, tokenClient(properties.getOidc()),
                identities, properties, clock);
    }

    public static KeycloakSecurity create(GatewaySecurityProperties properties, SessionCookieCodec codec,
                                          SessionCookies cookies, Clock clock) {
        GatewaySecurityProperties.Oidc oidc = properties.getOidc();
        SecurityChains.requireText(oidc.getIssuer(), "gateway.security.oidc.issuer");
        SecurityChains.requireText(oidc.getClientId(), "gateway.security.oidc.client-id");
        SecurityChains.requireText(oidc.getClientSecret(), "gateway.security.oidc.client-secret");
        SecurityChains.requireText(oidc.getAudience(), "gateway.security.oidc.audience");
        return new KeycloakSecurity(properties, codec, cookies, clock);
    }

    public GatewaySessionManager sessionManager() {
        return sessionManager;
    }

    public ServerHttpSecurity configure(ServerHttpSecurity http, SessionSecurityContextRepository contextRepository,
                                        ServerRequestCache requestCache) {
        DefaultServerOAuth2AuthorizationRequestResolver resolver = new DefaultServerOAuth2AuthorizationRequestResolver(
                registrations, new PathPatternParserServerWebExchangeMatcher(AUTHORIZATION_PATH));
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

        Converter<Jwt, Mono<AbstractAuthenticationToken>> bearerIdentity = jwt -> Mono.just(new GatewayAuthenticationToken(
                identities.identity(jwt, jwt.getClaimAsString("sid"), GatewayIdentity.AuthMethod.BEARER)));

        return SecurityChains.common(http, properties, contextRepository, requestCache,
                        new BearerTokenServerAuthenticationEntryPoint())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .oauth2Login(login -> login
                        .clientRegistrationRepository(registrations)
                        .authorizationRequestResolver(resolver)
                        .authorizationRequestRepository(new CookieAuthorizationRequestRepository(
                                codec, cookies, properties.getSession().getAuthzCookieName()))
                        .authenticationMatcher(new PathPatternParserServerWebExchangeMatcher(
                                CookieAuthorizationRequestRepository.CALLBACK_PATH + "/{registrationId}"))
                        .authorizedClientRepository(new KeycloakAuthorizedClientRepository(sessionManager))
                        .securityContextRepository(contextRepository)
                        .authenticationSuccessHandler(LoginOutcomeHandlers.success(requestCache))
                        .authenticationFailureHandler(LoginOutcomeHandlers.oidcFailure())
                        .loginPage(SecurityChains.loginPath(properties)))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtDecoder(accessTokenDecoder).jwtAuthenticationConverter(bearerIdentity))
                        .authenticationFailureHandler(lenientBearerFailure()));
    }

    /**
     * An invalid bearer token does not end the request here: public routes may
     * receive tokens meant for the backend. The route filter decides, using
     * the {@link AccessProblem#INVALID_TOKEN} marker.
     */
    static ServerAuthenticationFailureHandler lenientBearerFailure() {
        return (webFilterExchange, exception) -> {
            webFilterExchange.getExchange().getAttributes().put(AccessAttributes.PROBLEM, AccessProblem.INVALID_TOKEN);
            return webFilterExchange.getChain().filter(webFilterExchange.getExchange());
        };
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
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withJwkSetUri(oidc.effectiveInternalIssuer() + "/protocol/openid-connect/certs")
                .webClient(WebClient.builder().build())
                .build();
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator(Duration.ofSeconds(30)));
        validators.add(new JwtIssuerValidator(oidc.getIssuer()));
        validators.add(new JwtClaimValidator<>(JwtClaimNames.AUD, aud -> audienceContains(aud, oidc.getAudience())));
        if (oidc.getRequiredTokenType() != null && !oidc.getRequiredTokenType().isBlank()) {
            validators.add(new JwtClaimValidator<>("typ", typ -> oidc.getRequiredTokenType().equals(typ)));
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    private static KeycloakTokenClient tokenClient(GatewaySecurityProperties.Oidc oidc) {
        String internalIssuer = oidc.effectiveInternalIssuer();
        return new KeycloakTokenClient(WebClient.builder().build(),
                internalIssuer + "/protocol/openid-connect/token",
                internalIssuer + "/protocol/openid-connect/logout",
                oidc.getClientId(), oidc.getClientSecret());
    }

    private static boolean audienceContains(Object aud, String expected) {
        if (aud instanceof Collection<?> values) {
            return values.stream().anyMatch(v -> expected.equals(String.valueOf(v)));
        }
        return aud != null && expected.equals(aud.toString());
    }
}
