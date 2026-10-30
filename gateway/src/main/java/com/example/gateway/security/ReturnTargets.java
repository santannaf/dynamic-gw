package com.example.gateway.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Where a login may send the browser back to: a path of this gateway or a
 * trusted origin. Anything else would turn the login into an open redirect.
 */
public final class ReturnTargets {

    private final Set<String> trustedOrigins;

    public ReturnTargets(List<String> trustedOrigins) {
        this.trustedOrigins = trustedOrigins.stream()
                .map(ReturnTargets::trustedOrigin)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** The target as a URI; empty when it is malformed or not allowed. */
    public Optional<URI> parse(String target) {
        try {
            return Optional.of(new URI(target)).filter(this::allows);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    public boolean allows(URI target) {
        if (target.isAbsolute()) {
            return target.getHost() != null && target.getRawUserInfo() == null
                    && trustedOrigins.contains(origin(target));
        }
        // Browsers read any run of leading slashes as an authority: ///evil.test
        // goes to evil.test, although java.net.URI sees a path and no authority.
        String relative = target.toString();
        return relative.startsWith("/") && !relative.startsWith("//");
    }

    private static String trustedOrigin(String value) {
        URI uri = URI.create(value);
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalStateException("gateway.security.front-origins entry '" + value
                    + "' must be an exact origin, such as https://app.example.com");
        }
        return origin(uri);
    }

    private static String origin(URI uri) {
        String origin = (uri.getScheme() + "://" + uri.getHost()).toLowerCase(Locale.ROOT);
        return uri.getPort() == -1 ? origin : origin + ":" + uri.getPort();
    }
}
