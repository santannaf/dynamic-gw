package com.example.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "gateway.security")
public class GatewaySecurityProperties {

    public static final String PROVIDER_KEYCLOAK = "keycloak";
    public static final String PROVIDER_LDAP = "ldap";

    private boolean enabled = false;
    private String provider = PROVIDER_KEYCLOAK;
    private String defaultAccess = "authenticated";
    private String publicBaseUrl;
    private Oidc oidc = new Oidc();
    private Authorities authorities = new Authorities();
    private Internal internal = new Internal();
    private IdentityHeaders identityHeaders = new IdentityHeaders();
    private Session session = new Session();
    private Ldap ldap = new Ldap();

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getProvider() { return provider; }

    public void setProvider(String provider) { this.provider = provider; }

    public String getDefaultAccess() { return defaultAccess; }

    public void setDefaultAccess(String defaultAccess) { this.defaultAccess = defaultAccess; }

    public String getPublicBaseUrl() { return publicBaseUrl; }

    public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }

    public Oidc getOidc() { return oidc; }

    public void setOidc(Oidc oidc) { this.oidc = oidc; }

    public Authorities getAuthorities() { return authorities; }

    public void setAuthorities(Authorities authorities) { this.authorities = authorities; }

    public Internal getInternal() { return internal; }

    public void setInternal(Internal internal) { this.internal = internal; }

    public IdentityHeaders getIdentityHeaders() { return identityHeaders; }

    public void setIdentityHeaders(IdentityHeaders identityHeaders) { this.identityHeaders = identityHeaders; }

    public Session getSession() { return session; }

    public void setSession(Session session) { this.session = session; }

    public Ldap getLdap() { return ldap; }

    public void setLdap(Ldap ldap) { this.ldap = ldap; }

    public static class Oidc {
        private String registrationId = "keycloak";
        /** Public issuer, as browsers see it and as it appears in the {@code iss} claim. */
        private String issuer;
        /** Issuer base URL reachable from the pods (token, JWKS); defaults to {@link #issuer}. */
        private String internalIssuer;
        private String clientId;
        private String clientSecret;
        private String audience = "tools-gateway";
        /** Required value of Keycloak's {@code typ} claim; keeps ID tokens out of the bearer path. */
        private String requiredTokenType = "Bearer";
        private List<String> scopes = new ArrayList<>(List.of("openid", "profile", "email"));

        public String getRegistrationId() { return registrationId; }

        public void setRegistrationId(String registrationId) { this.registrationId = registrationId; }

        public String getIssuer() { return issuer; }

        public void setIssuer(String issuer) { this.issuer = issuer; }

        public String getInternalIssuer() { return internalIssuer; }

        public void setInternalIssuer(String internalIssuer) { this.internalIssuer = internalIssuer; }

        public String getClientId() { return clientId; }

        public void setClientId(String clientId) { this.clientId = clientId; }

        public String getClientSecret() { return clientSecret; }

        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }

        public String getAudience() { return audience; }

        public void setAudience(String audience) { this.audience = audience; }

        public String getRequiredTokenType() { return requiredTokenType; }

        public void setRequiredTokenType(String requiredTokenType) { this.requiredTokenType = requiredTokenType; }

        public List<String> getScopes() { return scopes; }

        public void setScopes(List<String> scopes) { this.scopes = scopes; }

        public String effectiveInternalIssuer() {
            return internalIssuer == null || internalIssuer.isBlank() ? issuer : internalIssuer;
        }
    }

    public static class Authorities {
        private List<String> groupClaims = new ArrayList<>(List.of("groups"));
        /** {@code {client-id}} is replaced by {@code gateway.security.oidc.client-id}. */
        private List<String> roleClaims = new ArrayList<>(List.of("realm_access.roles", "resource_access.{client-id}.roles"));

        public List<String> getGroupClaims() { return groupClaims; }

        public void setGroupClaims(List<String> groupClaims) { this.groupClaims = groupClaims; }

        public List<String> getRoleClaims() { return roleClaims; }

        public void setRoleClaims(List<String> roleClaims) { this.roleClaims = roleClaims; }
    }

    public static class Internal {
        private List<String> anyOf = new ArrayList<>(List.of("role:gateway-admin"));

        public List<String> getAnyOf() { return anyOf; }

        public void setAnyOf(List<String> anyOf) { this.anyOf = anyOf; }
    }

    public static class IdentityHeaders {
        private String prefix = "X-Auth-Request-";
        private int maxLength = 4096;

        public String getPrefix() { return prefix; }

        public void setPrefix(String prefix) { this.prefix = prefix; }

        public int getMaxLength() { return maxLength; }

        public void setMaxLength(int maxLength) { this.maxLength = maxLength; }
    }

    public static class Session {
        private String cookieName = "GW_SESSION";
        private String authzCookieName = "GW_AUTHZ";
        private boolean cookieSecure = true;
        /** First key encrypts; every key decrypts (rotation). */
        private List<Key> keys = new ArrayList<>();
        private int cacheMaxEntries = 10_000;
        /** Absolute lifetime of an LDAP session; Keycloak sessions follow the realm. */
        private Duration maxAge = Duration.ofHours(10);

        public String getCookieName() { return cookieName; }

        public void setCookieName(String cookieName) { this.cookieName = cookieName; }

        public String getAuthzCookieName() { return authzCookieName; }

        public void setAuthzCookieName(String authzCookieName) { this.authzCookieName = authzCookieName; }

        public boolean isCookieSecure() { return cookieSecure; }

        public void setCookieSecure(boolean cookieSecure) { this.cookieSecure = cookieSecure; }

        public List<Key> getKeys() { return keys; }

        public void setKeys(List<Key> keys) { this.keys = keys; }

        public int getCacheMaxEntries() { return cacheMaxEntries; }

        public void setCacheMaxEntries(int cacheMaxEntries) { this.cacheMaxEntries = cacheMaxEntries; }

        public Duration getMaxAge() { return maxAge; }

        public void setMaxAge(Duration maxAge) { this.maxAge = maxAge; }

        public static class Key {
            private String id;
            /** Base64 of 32 random bytes. */
            private String secret;

            public String getId() { return id; }

            public void setId(String id) { this.id = id; }

            public String getSecret() { return secret; }

            public void setSecret(String secret) { this.secret = secret; }
        }
    }

    public static class Ldap {
        private String url;
        private String bindDn;
        private String bindPassword;
        private boolean startTls = false;
        /** PEM file with the CA(s) that sign the directory certificate; empty = JVM default trust. */
        private String trustedCertificates;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration responseTimeout = Duration.ofSeconds(5);
        private int poolSize = 4;
        private Search userSearch = new Search("(uid={0})");
        private String usernameAttribute = "uid";
        private String emailAttribute = "mail";
        private String subjectAttribute = "entryUUID";
        private GroupSearch groupSearch = new GroupSearch();
        private Duration revalidateAfter = Duration.ofMinutes(5);
        private LoginAttempts loginAttempts = new LoginAttempts();

        public String getUrl() { return url; }

        public void setUrl(String url) { this.url = url; }

        public String getBindDn() { return bindDn; }

        public void setBindDn(String bindDn) { this.bindDn = bindDn; }

        public String getBindPassword() { return bindPassword; }

        public void setBindPassword(String bindPassword) { this.bindPassword = bindPassword; }

        public boolean isStartTls() { return startTls; }

        public void setStartTls(boolean startTls) { this.startTls = startTls; }

        public String getTrustedCertificates() { return trustedCertificates; }

        public void setTrustedCertificates(String trustedCertificates) { this.trustedCertificates = trustedCertificates; }

        public Duration getConnectTimeout() { return connectTimeout; }

        public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }

        public Duration getResponseTimeout() { return responseTimeout; }

        public void setResponseTimeout(Duration responseTimeout) { this.responseTimeout = responseTimeout; }

        public int getPoolSize() { return poolSize; }

        public void setPoolSize(int poolSize) { this.poolSize = poolSize; }

        public Search getUserSearch() { return userSearch; }

        public void setUserSearch(Search userSearch) { this.userSearch = userSearch; }

        public String getUsernameAttribute() { return usernameAttribute; }

        public void setUsernameAttribute(String usernameAttribute) { this.usernameAttribute = usernameAttribute; }

        public String getEmailAttribute() { return emailAttribute; }

        public void setEmailAttribute(String emailAttribute) { this.emailAttribute = emailAttribute; }

        public String getSubjectAttribute() { return subjectAttribute; }

        public void setSubjectAttribute(String subjectAttribute) { this.subjectAttribute = subjectAttribute; }

        public GroupSearch getGroupSearch() { return groupSearch; }

        public void setGroupSearch(GroupSearch groupSearch) { this.groupSearch = groupSearch; }

        public Duration getRevalidateAfter() { return revalidateAfter; }

        public void setRevalidateAfter(Duration revalidateAfter) { this.revalidateAfter = revalidateAfter; }

        public LoginAttempts getLoginAttempts() { return loginAttempts; }

        public void setLoginAttempts(LoginAttempts loginAttempts) { this.loginAttempts = loginAttempts; }

        public static class Search {
            private String base;
            /** {@code {0}} is the escaped username (user search) or the user DN (group search). */
            private String filter;

            public Search() {
            }

            Search(String filter) {
                this.filter = filter;
            }

            public String getBase() { return base; }

            public void setBase(String base) { this.base = base; }

            public String getFilter() { return filter; }

            public void setFilter(String filter) { this.filter = filter; }
        }

        public static class GroupSearch extends Search {
            private String nameAttribute = "cn";

            public GroupSearch() {
                super("(member={0})");
            }

            public String getNameAttribute() { return nameAttribute; }

            public void setNameAttribute(String nameAttribute) { this.nameAttribute = nameAttribute; }
        }

        public static class LoginAttempts {
            private int maxFailures = 5;
            private int maxFailuresPerClient = 20;
            private Duration window = Duration.ofMinutes(5);

            public int getMaxFailures() { return maxFailures; }

            public void setMaxFailures(int maxFailures) { this.maxFailures = maxFailures; }

            public int getMaxFailuresPerClient() { return maxFailuresPerClient; }

            public void setMaxFailuresPerClient(int maxFailuresPerClient) { this.maxFailuresPerClient = maxFailuresPerClient; }

            public Duration getWindow() { return window; }

            public void setWindow(Duration window) { this.window = window; }
        }
    }
}
