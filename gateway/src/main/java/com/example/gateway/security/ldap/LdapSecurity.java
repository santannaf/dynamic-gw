package com.example.gateway.security.ldap;

import com.example.gateway.access.UnauthenticatedHandler;
import com.example.gateway.security.BrowserLoginHandler;
import com.example.gateway.security.GatewaySecurityProperties;
import com.example.gateway.security.IdentityChannel;
import com.example.gateway.security.SecurityChains;
import com.example.gateway.security.session.BrowserSession;
import com.example.gateway.security.session.GatewaySessionManager;
import com.example.gateway.security.session.SessionSecurityContextRepository;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.savedrequest.ServerRequestCache;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;

import java.time.Clock;
import java.util.Set;

/**
 * Provider {@code ldap}: the gateway authenticates against the directory
 * itself (AD or any LDAP server). No bearer tokens in this mode: the
 * {@code Authorization} header is not interpreted and travels upstream as is.
 */
public final class LdapSecurity implements IdentityChannel {

    private final GatewaySecurityProperties properties;
    private final BrowserSession session;
    private final LdapDirectory directory;
    private final LoginAttemptLimiter limiter;
    private final LdapSessionManager sessionManager;
    private final BrowserLoginHandler browserLogin;

    private LdapSecurity(GatewaySecurityProperties properties, BrowserSession session, Clock clock) {
        this.properties = properties;
        this.session = session;
        this.directory = new LdapDirectory(properties.getLdap());
        GatewaySecurityProperties.Ldap.LoginAttempts attempts = properties.getLdap().getLoginAttempts();
        this.limiter = new LoginAttemptLimiter(attempts.getMaxFailures(), attempts.getMaxFailuresPerClient(),
                attempts.getWindow(), clock);
        this.sessionManager = new LdapSessionManager(session.codec(), session.cookies(), directory, properties, clock);
        this.browserLogin = new BrowserLoginHandler(session, LdapLogin.LOGIN_PATH, UnauthenticatedHandler.PLAIN_401);
    }

    public static LdapSecurity create(GatewaySecurityProperties properties, Clock clock) {
        BrowserSession session = BrowserSession.from(properties);
        GatewaySecurityProperties.Ldap ldap = properties.getLdap();
        SecurityChains.requireText(ldap.getUrl(), "gateway.security.ldap.url");
        SecurityChains.requireText(ldap.getBindDn(), "gateway.security.ldap.bind-dn");
        SecurityChains.requireText(ldap.getBindPassword(), "gateway.security.ldap.bind-password");
        SecurityChains.requireText(ldap.getUserSearch().getBase(), "gateway.security.ldap.user-search.base");
        SecurityChains.requireText(ldap.getUserSearch().getFilter(), "gateway.security.ldap.user-search.filter");
        SecurityChains.requireText(ldap.getGroupSearch().getBase(), "gateway.security.ldap.group-search.base");
        SecurityChains.requireText(ldap.getGroupSearch().getFilter(), "gateway.security.ldap.group-search.filter");
        return new LdapSecurity(properties, session, clock);
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
        PathPatternParserServerWebExchangeMatcher loginPost =
                new PathPatternParserServerWebExchangeMatcher(LdapLogin.LOGIN_PATH, HttpMethod.POST);

        AuthenticationWebFilter login = new AuthenticationWebFilter(LdapLogin.authenticationManager(directory, limiter));
        login.setRequiresAuthenticationMatcher(loginPost);
        login.setServerAuthenticationConverter(LdapLogin.converter());
        login.setAuthenticationSuccessHandler(LdapLogin.success(sessionManager, limiter, session));
        login.setAuthenticationFailureHandler(LdapLogin.failure(limiter));
        login.setSecurityContextRepository(contextRepository);

        // Double-submit CSRF on the login form only; the cookie never leaves /_gateway/login.
        CookieServerCsrfTokenRepository csrfTokens = new CookieServerCsrfTokenRepository();
        csrfTokens.setCookiePath(LdapLogin.LOGIN_PATH);
        boolean secure = properties.getSession().isCookieSecure();
        csrfTokens.setCookieCustomizer(cookie -> cookie.secure(secure).sameSite("Strict"));

        return SecurityChains.common(http, properties, SecurityChains.browserLoginEntryPoint(
                        LdapLogin.LOGIN_PATH, requestCache, SecurityChains.statusEntryPoint()))
                .securityContextRepository(contextRepository)
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens).requireCsrfProtectionMatcher(loginPost))
                .addFilterAt(login, SecurityWebFiltersOrder.FORM_LOGIN);
    }

    @Override
    public void close() {
        directory.close();
    }
}
