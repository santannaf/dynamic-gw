package com.example.gateway.access;

import org.springframework.http.HttpHeaders;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Identity headers sent to upstream services. Incoming headers with the same
 * prefix are always removed first: a backend that trusts them must only ever
 * see values the gateway verified.
 */
public final class IdentityHeaders {

    static final String USER = "User";
    static final String EMAIL = "Email";
    static final String SUBJECT = "Subject";
    static final String GROUPS = "Groups";
    static final String ROLES = "Roles";
    static final String AUTHORITIES = "Authorities";
    static final String CLIENT_ID = "Client-Id";
    static final String TRUNCATED_SUFFIX = "-Truncated";

    private IdentityHeaders() {
    }

    public static void strip(HttpHeaders headers, String prefix) {
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        List<String> spoofed = new ArrayList<>();
        for (String name : headers.headerNames()) {
            if (name.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                spoofed.add(name);
            }
        }
        spoofed.forEach(headers::remove);
    }

    public static void inject(HttpHeaders headers, GatewayIdentity identity, String prefix, int maxLength) {
        setIfPresent(headers, prefix + USER, identity.username());
        setIfPresent(headers, prefix + EMAIL, identity.email());
        setIfPresent(headers, prefix + SUBJECT, identity.subject());
        setList(headers, prefix + GROUPS, identity.groups(), maxLength);
        setList(headers, prefix + ROLES, identity.roles(), maxLength);
        setList(headers, prefix + AUTHORITIES,
                identity.authorities().stream().sorted().map(IdentityHeaders::escapeSeparator).toList(), maxLength);
        if (identity.method() == GatewayIdentity.AuthMethod.BEARER) {
            setIfPresent(headers, prefix + CLIENT_ID, identity.clientId());
        }
    }

    /** Removes the named cookies from every {@code Cookie} header, keeping the other cookies. */
    public static void stripCookies(HttpHeaders headers, Set<String> cookieNames) {
        List<String> cookieHeaders = headers.get(HttpHeaders.COOKIE);
        if (cookieHeaders == null || cookieHeaders.isEmpty()) {
            return;
        }
        List<String> kept = new ArrayList<>(cookieHeaders.size());
        for (String header : cookieHeaders) {
            StringBuilder rebuilt = new StringBuilder(header.length());
            for (String pair : header.split(";")) {
                String trimmed = pair.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                String name = eq < 0 ? trimmed : trimmed.substring(0, eq).trim();
                if (cookieNames.contains(name)) {
                    continue;
                }
                if (!rebuilt.isEmpty()) {
                    rebuilt.append("; ");
                }
                rebuilt.append(trimmed);
            }
            if (!rebuilt.isEmpty()) {
                kept.add(rebuilt.toString());
            }
        }
        if (kept.isEmpty()) {
            headers.remove(HttpHeaders.COOKIE);
        } else {
            headers.put(HttpHeaders.COOKIE, kept);
        }
    }

    private static void setIfPresent(HttpHeaders headers, String name, String value) {
        if (value != null && !value.isBlank()) {
            headers.set(name, sanitize(value));
        }
    }

    private static void setList(HttpHeaders headers, String name, List<String> values, int maxLength) {
        if (values.isEmpty()) {
            return;
        }
        String joined = sanitize(String.join(",", values));
        if (joined.length() > maxLength) {
            int cut = joined.lastIndexOf(',', maxLength);
            joined = cut > 0 ? joined.substring(0, cut) : joined.substring(0, maxLength);
            headers.set(name + TRUNCATED_SUFFIX, "true");
        }
        headers.set(name, joined);
    }

    // Each entry carries its type, so a comma inside a value from the identity
    // provider could forge another entry, such as role:gateway-admin.
    private static String escapeSeparator(String authority) {
        return authority.replace("%", "%25").replace(",", "%2C");
    }

    // Header values cannot carry control characters; values come from the
    // identity provider, so this only guards against odd directory data.
    private static String sanitize(String value) {
        StringBuilder out = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean control = c < 0x20 && c != '\t' || c == 0x7f;
            if (control && out == null) {
                out = new StringBuilder(value.length()).append(value, 0, i);
            }
            if (out != null) {
                out.append(control ? ' ' : c);
            }
        }
        return out == null ? value : out.toString();
    }
}
