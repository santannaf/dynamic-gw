package com.example.gateway.security.support;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSimpleBindRequest;
import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldif.LDIFException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory directory shaped like the IDM's AD: an OU per application holding
 * its access groups, plus an unrelated OU that must not leak into authorities.
 * Listens on a fixed port so it can be stopped and restarted on the same URL.
 */
public final class TestDirectory implements AutoCloseable {

    public static final String BASE = "dc=tools,dc=local";
    public static final String SERVICE_DN = "cn=gateway,ou=services," + BASE;
    public static final String SERVICE_PASSWORD = "service-pass";

    private final InMemoryDirectoryServer server;
    private final int port;
    private final AtomicInteger userBinds = new AtomicInteger();

    public TestDirectory() {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE);
            config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("default", port));
            config.setSchema(null);
            config.addInMemoryOperationInterceptor(new InMemoryOperationInterceptor() {
                @Override
                public void processSimpleBindRequest(InMemoryInterceptedSimpleBindRequest request) {
                    if (!SERVICE_DN.equalsIgnoreCase(request.getRequest().getBindDN())) {
                        userBinds.incrementAndGet();
                    }
                }
            });
            server = new InMemoryDirectoryServer(config);
            server.add("dn: " + BASE, "objectClass: top", "objectClass: domain", "dc: tools");
            for (String ou : new String[]{"services", "people", "tools", "other"}) {
                server.add("dn: ou=" + ou + "," + BASE, "objectClass: organizationalUnit", "ou: " + ou);
            }
            server.add("dn: " + SERVICE_DN, "objectClass: person", "cn: gateway", "sn: gateway",
                    "userPassword: " + SERVICE_PASSWORD);
            user("alice", "alice-pass", "512");
            user("bob", "bob-pass", "512");
            user("dave", "dave-pass", "514");
            server.add("dn: cn=tools-httpbin,ou=tools," + BASE, "objectClass: groupOfNames", "cn: tools-httpbin",
                    "member: " + userDn("alice"));
            server.add("dn: cn=gateway-admins,ou=tools," + BASE, "objectClass: groupOfNames", "cn: gateway-admins",
                    "member: " + userDn("carol-placeholder"));
            server.add("dn: cn=finance,ou=other," + BASE, "objectClass: groupOfNames", "cn: finance",
                    "member: " + userDn("alice"));
            server.startListening();
        } catch (LDAPException | LDIFException e) {
            throw new IllegalStateException(e);
        }
    }

    public String url() {
        return "ldap://127.0.0.1:" + port;
    }

    public static String userDn(String uid) {
        return "uid=" + uid + ",ou=people," + BASE;
    }

    public int userBinds() {
        return userBinds.get();
    }

    public void removeFromGroup(String uid, String group) {
        try {
            server.modify("cn=" + group + ",ou=tools," + BASE,
                    new Modification(ModificationType.DELETE, "member", userDn(uid)));
        } catch (LDAPException e) {
            throw new IllegalStateException(e);
        }
    }

    public void addToGroup(String uid, String group) {
        try {
            server.modify("cn=" + group + ",ou=tools," + BASE,
                    new Modification(ModificationType.ADD, "member", userDn(uid)));
        } catch (LDAPException e) {
            throw new IllegalStateException(e);
        }
    }

    public void stop() {
        server.shutDown(true);
    }

    public void start() {
        try {
            server.startListening();
        } catch (LDAPException e) {
            throw new IllegalStateException(e);
        }
    }

    private void user(String uid, String password, String userAccountControl) throws LDAPException, LDIFException {
        server.add("dn: " + userDn(uid), "objectClass: inetOrgPerson", "uid: " + uid, "cn: " + uid, "sn: " + uid,
                "mail: " + uid + "@tools.local", "userPassword: " + password,
                "userAccountControl: " + userAccountControl);
    }

    @Override
    public void close() {
        server.shutDown(true);
    }
}
