package com.example.gateway.security.ldap;

import com.example.gateway.security.GatewaySecurityProperties;
import com.example.gateway.security.SecurityChains;
import com.example.gateway.security.session.GatewaySessionManager;
import com.example.gateway.security.session.SessionCookieCodec;
import com.example.gateway.security.session.SessionCookies;
import com.example.gateway.security.session.SessionSecurityContextRepository;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.savedrequest.ServerRequestCache;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;

import java.time.Clock;

/**
 * Provider {@code ldap}: the gateway authenticates against the directory
 * itself (AD or any LDAP server). No bearer tokens in this mode: the
 * {@code Authorization} header is not interpreted and travels upstream as is.
 *
 * Plain factory rather than a {@code @Configuration}: the provider is picked
 * at startup, and the native image evaluates {@code @Conditional} at build time.
 */
public final class LdapSecurity implements AutoCloseable {

    private final GatewaySecurityProperties properties;
    private final LdapDirectory directory;
    private final LoginAttemptLimiter limiter;
    private final LdapSessionManager sessionManager;

    private LdapSecurity(GatewaySecurityProperties properties, SessionCookieCodec codec, SessionCookies cookies,
                         Clock clock) {
        this.properties = properties;
        this.directory = new LdapDirectory(properties.getLdap());
        GatewaySecurityProperties.Ldap.LoginAttempts attempts = properties.getLdap().getLoginAttempts();
        this.limiter = new LoginAttemptLimiter(attempts.getMaxFailures(), attempts.getMaxFailuresPerClient(),
                attempts.getWindow(), clock);
        this.sessionManager = new LdapSessionManager(codec, cookies, directory, properties, clock);
    }

    public static LdapSecurity create(GatewaySecurityProperties properties, SessionCookieCodec codec,
                                      SessionCookies cookies, Clock clock) {
        GatewaySecurityProperties.Ldap ldap = properties.getLdap();
        SecurityChains.requireText(ldap.getUrl(), "gateway.security.ldap.url");
        SecurityChains.requireText(ldap.getBindDn(), "gateway.security.ldap.bind-dn");
        SecurityChains.requireText(ldap.getBindPassword(), "gateway.security.ldap.bind-password");
        SecurityChains.requireText(ldap.getUserSearch().getBase(), "gateway.security.ldap.user-search.base");
        SecurityChains.requireText(ldap.getUserSearch().getFilter(), "gateway.security.ldap.user-search.filter");
        SecurityChains.requireText(ldap.getGroupSearch().getBase(), "gateway.security.ldap.group-search.base");
        SecurityChains.requireText(ldap.getGroupSearch().getFilter(), "gateway.security.ldap.group-search.filter");
        return new LdapSecurity(properties, codec, cookies, clock);
    }

    public GatewaySessionManager sessionManager() {
        return sessionManager;
    }

    public ServerHttpSecurity configure(ServerHttpSecurity http, SessionSecurityContextRepository contextRepository,
                                        ServerRequestCache requestCache) {
        PathPatternParserServerWebExchangeMatcher loginPost =
                new PathPatternParserServerWebExchangeMatcher(LdapLogin.LOGIN_PATH, HttpMethod.POST);

        AuthenticationWebFilter login = new AuthenticationWebFilter(LdapLogin.authenticationManager(directory, limiter));
        login.setRequiresAuthenticationMatcher(loginPost);
        login.setServerAuthenticationConverter(LdapLogin.converter());
        login.setAuthenticationSuccessHandler(LdapLogin.success(sessionManager, limiter, requestCache));
        login.setAuthenticationFailureHandler(LdapLogin.failure(limiter));
        login.setSecurityContextRepository(contextRepository);

        // Double-submit CSRF on the login form only; the cookie never leaves /_gateway/login.
        CookieServerCsrfTokenRepository csrfTokens = new CookieServerCsrfTokenRepository();
        csrfTokens.setCookiePath(LdapLogin.LOGIN_PATH);
        boolean secure = properties.getSession().isCookieSecure();
        csrfTokens.setCookieCustomizer(cookie -> cookie.secure(secure).sameSite("Strict"));

        return SecurityChains.common(http, properties, contextRepository, requestCache, SecurityChains.statusEntryPoint())
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens).requireCsrfProtectionMatcher(loginPost))
                .addFilterAt(login, SecurityWebFiltersOrder.FORM_LOGIN);
    }

    @Override
    public void close() {
        directory.close();
    }
}
