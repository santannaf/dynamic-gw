package com.example.gateway.security.ldap;

import com.example.gateway.security.GatewaySecurityProperties;
import com.example.gateway.security.session.ProviderUnavailableException;
import com.unboundid.ldap.sdk.BindRequest;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.LDAPConnectionPool;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPSearchException;
import com.unboundid.ldap.sdk.LDAPURL;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.SimpleBindRequest;
import com.unboundid.ldap.sdk.SingleServerSet;
import com.unboundid.ldap.sdk.StartTLSPostConnectProcessor;
import com.unboundid.util.ssl.PEMFileTrustManager;
import com.unboundid.util.ssl.SSLUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.File;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Blocking directory access (callers move it off the event loop). A pool
 * bound as the service account serves searches; user binds use a fresh
 * connection so a user's credentials never stick to a pooled connection.
 * The pool is created lazily, so the gateway starts while the directory is
 * down.
 */
public class LdapDirectory implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LdapDirectory.class);
    private static final String USER_ACCOUNT_CONTROL = "userAccountControl";
    private static final int ACCOUNT_DISABLED = 0x2;
    private static final String OBJECT_GUID = "objectGUID";
    private static final Set<ResultCode> UNAVAILABLE = Set.of(
            ResultCode.SERVER_DOWN, ResultCode.CONNECT_ERROR, ResultCode.TIMEOUT, ResultCode.UNAVAILABLE,
            ResultCode.BUSY, ResultCode.LOCAL_ERROR, ResultCode.DECODING_ERROR, ResultCode.ENCODING_ERROR);

    private final GatewaySecurityProperties.Ldap properties;
    private final SingleServerSet serverSet;
    private final BindRequest serviceBind;
    private final String[] userAttributes;
    private volatile LDAPConnectionPool pool;

    public LdapDirectory(GatewaySecurityProperties.Ldap properties) {
        this.properties = properties;
        try {
            LDAPURL url = new LDAPURL(properties.getUrl());
            LDAPConnectionOptions options = new LDAPConnectionOptions();
            options.setConnectTimeoutMillis((int) properties.getConnectTimeout().toMillis());
            options.setResponseTimeoutMillis(properties.getResponseTimeout().toMillis());
            boolean ldaps = "ldaps".equalsIgnoreCase(url.getScheme());
            if (ldaps) {
                this.serverSet = new SingleServerSet(url.getHost(), url.getPort(), sslSocketFactory(), options);
            } else if (properties.isStartTls()) {
                this.serverSet = new SingleServerSet(url.getHost(), url.getPort(), SocketFactory.getDefault(), options,
                        null, new StartTLSPostConnectProcessor(sslSocketFactory()));
            } else {
                this.serverSet = new SingleServerSet(url.getHost(), url.getPort(), options);
            }
        } catch (LDAPException e) {
            throw new IllegalStateException("gateway.security.ldap.url is not a valid LDAP URL: " + properties.getUrl(), e);
        }
        this.serviceBind = new SimpleBindRequest(properties.getBindDn(), properties.getBindPassword());
        this.userAttributes = new String[]{
                properties.getUsernameAttribute(),
                properties.getEmailAttribute(),
                properties.getSubjectAttribute(),
                USER_ACCOUNT_CONTROL};
    }

    /**
     * Authenticates with the user's own bind. Empty password is refused before
     * anything reaches the directory: a simple bind with an empty password is
     * an anonymous bind, which most servers accept.
     */
    public Optional<LdapUser> authenticate(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            return Optional.empty();
        }
        SearchResultEntry entry = findUser(username.trim());
        if (entry == null) {
            return Optional.empty();
        }
        try (LDAPConnection connection = serverSet.getConnection()) {
            connection.bind(new SimpleBindRequest(entry.getDN(), password));
        } catch (LDAPException e) {
            if (UNAVAILABLE.contains(e.getResultCode())) {
                throw unavailable(e);
            }
            return Optional.empty();
        }
        if (isDisabled(entry)) {
            return Optional.empty();
        }
        return Optional.of(toUser(entry));
    }

    /** Current state of a user by DN; empty when the user is gone or disabled. */
    public Optional<LdapUser> lookup(String dn) {
        SearchResultEntry entry;
        try {
            entry = pool().getEntry(dn, userAttributes);
        } catch (LDAPException e) {
            if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) {
                return Optional.empty();
            }
            throw unavailable(e);
        }
        if (entry == null || isDisabled(entry)) {
            return Optional.empty();
        }
        return Optional.of(toUser(entry));
    }

    @Override
    public void close() {
        LDAPConnectionPool current = pool;
        if (current != null) {
            current.close();
        }
    }

    private SearchResultEntry findUser(String username) {
        GatewaySecurityProperties.Ldap.Search search = properties.getUserSearch();
        try {
            Filter filter = Filter.create(search.getFilter().replace("{0}", Filter.encodeValue(username)));
            return pool().searchForEntry(search.getBase(), SearchScope.SUB, filter, userAttributes);
        } catch (LDAPSearchException e) {
            if (e.getResultCode() == ResultCode.SIZE_LIMIT_EXCEEDED) {
                log.warn("LDAP user search matched more than one entry; refusing login");
                return null;
            }
            if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) {
                return null;
            }
            throw unavailable(e);
        } catch (LDAPException e) {
            throw unavailable(e);
        }
    }

    private List<String> groupsOf(String dn) {
        GatewaySecurityProperties.Ldap.GroupSearch search = properties.getGroupSearch();
        try {
            Filter filter = Filter.create(search.getFilter().replace("{0}", Filter.encodeValue(dn)));
            SearchResult result = pool().search(search.getBase(), SearchScope.SUB, filter, search.getNameAttribute());
            return result.getSearchEntries().stream()
                    .map(group -> group.getAttributeValue(search.getNameAttribute()))
                    .filter(name -> name != null && !name.isBlank())
                    .sorted()
                    .toList();
        } catch (LDAPException e) {
            throw unavailable(e);
        }
    }

    private LdapUser toUser(SearchResultEntry entry) {
        return new LdapUser(
                entry.getDN(),
                subjectOf(entry),
                entry.getAttributeValue(properties.getUsernameAttribute()),
                entry.getAttributeValue(properties.getEmailAttribute()),
                groupsOf(entry.getDN()));
    }

    private String subjectOf(SearchResultEntry entry) {
        String attribute = properties.getSubjectAttribute();
        if (OBJECT_GUID.equalsIgnoreCase(attribute)) {
            byte[] guid = entry.getAttributeValueBytes(attribute);
            if (guid != null && guid.length == 16) {
                return activeDirectoryGuid(guid).toString();
            }
        } else {
            String value = entry.getAttributeValue(attribute);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return entry.getDN();
    }

    private static boolean isDisabled(SearchResultEntry entry) {
        Integer flags = entry.getAttributeValueAsInteger(USER_ACCOUNT_CONTROL);
        return flags != null && (flags & ACCOUNT_DISABLED) != 0;
    }

    // AD stores the first three GUID fields little-endian.
    static UUID activeDirectoryGuid(byte[] b) {
        ByteBuffer ordered = ByteBuffer.wrap(new byte[]{
                b[3], b[2], b[1], b[0], b[5], b[4], b[7], b[6],
                b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]});
        return new UUID(ordered.getLong(), ordered.getLong());
    }

    private LDAPConnectionPool pool() throws LDAPException {
        LDAPConnectionPool current = pool;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (pool == null) {
                LDAPConnectionPool created = new LDAPConnectionPool(serverSet, serviceBind, 1, Math.max(1, properties.getPoolSize()));
                // A directory restart leaves dead pooled connections behind; retry once on a fresh one.
                created.setRetryFailedOperationsDueToInvalidConnections(true);
                pool = created;
            }
            return pool;
        }
    }

    private SSLSocketFactory sslSocketFactory() {
        try {
            String pem = properties.getTrustedCertificates();
            SSLUtil ssl = pem == null || pem.isBlank()
                    ? new SSLUtil()
                    : new SSLUtil(new PEMFileTrustManager(new File(pem)));
            return ssl.createSSLSocketFactory();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot build the LDAP TLS socket factory", e);
        }
    }

    private static ProviderUnavailableException unavailable(LDAPException e) {
        return new ProviderUnavailableException("LDAP " + e.getResultCode() + ": " + e.getMessage(), e);
    }
}
