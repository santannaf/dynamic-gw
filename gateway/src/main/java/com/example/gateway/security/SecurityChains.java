package com.example.gateway.security;

import com.example.gateway.access.BrowserNavigation;
import com.example.gateway.access.RouteAccessFilter;
import com.example.gateway.security.session.SessionSecurityContextRepository;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.DelegatingServerAuthenticationEntryPoint;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.HttpStatusServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.security.web.server.savedrequest.ServerRequestCache;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;

/** Pieces of the Spring Security chain shared by both providers and by the disabled mode. */
public final class SecurityChains {

    private SecurityChains() {
    }

    /**
     * {@code gateway.security.enabled=false}: no authentication at all, but
     * Spring Security is on the classpath, so an explicit permit-all chain
     * replaces Boot's default (basic auth with a generated password). Defaults
     * that would touch proxied traffic (security headers, WebSession, the
     * generated /login and /logout pages) are switched off.
     */
    public static SecurityWebFilterChain permitAll(ServerHttpSecurity http) {
        return http
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .headers(ServerHttpSecurity.HeaderSpec::disable)
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .build();
    }

    /**
     * Parts both providers share: cookie-backed security context, no
     * WebSession, no generated login/logout pages, no security headers on
     * proxied responses, and authorization only for {@code GET /internal/routes}
     * (route-level decisions happen in {@link RouteAccessFilter}, once the
     * route is known).
     */
    public static ServerHttpSecurity common(ServerHttpSecurity http,
                                            GatewaySecurityProperties properties,
                                            SessionSecurityContextRepository contextRepository,
                                            ServerRequestCache requestCache,
                                            ServerAuthenticationEntryPoint fallbackEntryPoint) {
        RedirectServerAuthenticationEntryPoint toLogin = new RedirectServerAuthenticationEntryPoint(loginPath(properties));
        toLogin.setRequestCache(requestCache);
        ServerWebExchangeMatcher browserNavigation = exchange -> BrowserNavigation.matches(exchange.getRequest())
                ? ServerWebExchangeMatcher.MatchResult.match()
                : ServerWebExchangeMatcher.MatchResult.notMatch();
        DelegatingServerAuthenticationEntryPoint entryPoint = new DelegatingServerAuthenticationEntryPoint(
                new DelegatingServerAuthenticationEntryPoint.DelegateEntry(browserNavigation, toLogin));
        entryPoint.setDefaultEntryPoint(fallbackEntryPoint);

        String[] internalAuthorities = properties.getInternal().getAnyOf().toArray(String[]::new);
        return http
                .securityContextRepository(contextRepository)
                // Spring's request-cache filter would expire REDIRECT_URI on every proxied response;
                // the cookie cache is used explicitly by the login redirect and the success handlers.
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .headers(ServerHttpSecurity.HeaderSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(new HttpStatusServerAccessDeniedHandler(HttpStatus.FORBIDDEN)))
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.GET, "/internal/routes").hasAnyAuthority(internalAuthorities)
                        .anyExchange().permitAll());
    }

    public static String loginPath(GatewaySecurityProperties properties) {
        return GatewaySecurityProperties.PROVIDER_LDAP.equals(properties.getProvider())
                ? "/_gateway/login"
                : "/_gateway/oauth2/authorization/" + properties.getOidc().getRegistrationId();
    }

    public static ServerAuthenticationEntryPoint statusEntryPoint() {
        return new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED);
    }

    public static void requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " is required when gateway.security.enabled=true");
        }
    }
}
