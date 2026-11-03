package com.example.shared.routes;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Rules of a route's target and policies. The operator uses them to keep an
 * invalid GatewayRoute out of the snapshot, and the gateway to do the same
 * with routes declared in properties, so both delivery modes accept exactly
 * the same routes.
 */
public final class RoutePolicyRules {

    /** Filters a route may declare and their required arguments, named as in Spring Cloud Gateway. */
    public static final Map<String, List<String>> FILTER_CATALOG = catalog();

    /** Prefix of the identity headers the gateway injects (its default); no filter may touch them. */
    public static final String DEFAULT_IDENTITY_HEADER_PREFIX = "X-Auth-Request-";

    private static final Set<String> REQUEST_HEADER_FILTERS =
            Set.of("AddRequestHeader", "SetRequestHeader", "RemoveRequestHeader");
    private static final Set<String> CALLER_KEYS = Set.of("route", "ip", "user", "client-id");
    private static final Pattern HEADER_KEY = Pattern.compile("^header:[A-Za-z0-9-]+$");
    private static final Pattern HEADER_NAME = Pattern.compile("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$");
    private static final Pattern DURATION = Pattern.compile("^([0-9]{1,9})(ms|s|m|h)$");
    private static final Pattern DATA_SIZE = Pattern.compile("^[0-9]{1,12}(B|KB|MB)?$");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[^}/]+}");
    private static final int MAX_BACKENDS = 10;

    private RoutePolicyRules() {
    }

    private static Map<String, List<String>> catalog() {
        Map<String, List<String>> catalog = new LinkedHashMap<>();
        catalog.put("AddRequestHeader", List.of("name", "value"));
        catalog.put("SetRequestHeader", List.of("name", "value"));
        catalog.put("RemoveRequestHeader", List.of("name"));
        catalog.put("AddResponseHeader", List.of("name", "value"));
        catalog.put("SetResponseHeader", List.of("name", "value"));
        catalog.put("RemoveResponseHeader", List.of("name"));
        catalog.put("RewritePath", List.of("regexp", "replacement"));
        catalog.put("PrefixPath", List.of("prefix"));
        catalog.put("SetPath", List.of("template"));
        catalog.put("AddRequestParameter", List.of("name", "value"));
        catalog.put("RemoveRequestParameter", List.of("name"));
        catalog.put("RequestSize", List.of("maxSize"));
        return Collections.unmodifiableMap(catalog);
    }

    /** {@code 500ms}, {@code 1s}, {@code 1m} or {@code 1h}; {@code null} for anything else. */
    public static Duration parseDuration(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = DURATION.matcher(value.strip());
        if (!matcher.matches()) {
            return null;
        }
        long amount = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2)) {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            default -> Duration.ofHours(amount);
        };
    }

    /**
     * First problem of the route's target and policies, or empty when the
     * route is valid. {@code identityHeaderPrefix} is the prefix of the
     * headers the gateway injects (case-insensitive).
     */
    public static Optional<String> validate(RouteConfigEntry route, String identityHeaderPrefix) {
        boolean hasTarget = route.targetUri() != null && !route.targetUri().isBlank();
        if (hasTarget && route.aggregate() != null) {
            return problem("declare either targetUri or aggregate, not both");
        }
        if (!hasTarget && route.aggregate() == null) {
            return problem("targetUri is required (or aggregate, to combine several backends)");
        }
        return first(
                rateLimit(route.rateLimit()),
                throttle(route.throttle()),
                filters(route.filters(), identityHeaderPrefix),
                transform("transform", route.transform()),
                aggregate(route.aggregate(), route.methods()));
    }

    @SafeVarargs
    private static Optional<String> first(Optional<String>... problems) {
        for (Optional<String> problem : problems) {
            if (problem.isPresent()) {
                return problem;
            }
        }
        return Optional.empty();
    }

    /** Problem of a rate limit on its own (also used for the gateway's global one). */
    public static Optional<String> rateLimit(RouteRateLimit rateLimit) {
        if (rateLimit == null) {
            return Optional.empty();
        }
        if (rateLimit.requests() == null || rateLimit.requests() < 1) {
            return problem("rateLimit.requests must be >= 1");
        }
        Duration period = parseDuration(rateLimit.period());
        if (period == null || period.isZero()) {
            return problem("rateLimit.period must be a positive duration such as 500ms, 1s, 1m or 1h, but was '"
                    + rateLimit.period() + "'");
        }
        if (rateLimit.burst() != null && rateLimit.burst() < 1) {
            return problem("rateLimit.burst must be >= 1");
        }
        String key = rateLimit.key();
        if (key != null && !CALLER_KEYS.contains(key) && !HEADER_KEY.matcher(key).matches()) {
            return problem("rateLimit.key must be route, ip, user, client-id or header:<name>, but was '" + key + "'");
        }
        return Optional.empty();
    }

    /** Problem of a throttle on its own (also used for the gateway's global one). */
    public static Optional<String> throttle(RouteThrottle throttle) {
        if (throttle == null) {
            return Optional.empty();
        }
        if (throttle.maxConcurrent() == null || throttle.maxConcurrent() < 1) {
            return problem("throttle.maxConcurrent must be >= 1");
        }
        if (throttle.maxQueue() != null && throttle.maxQueue() < 0) {
            return problem("throttle.maxQueue must be >= 0");
        }
        if (throttle.maxWait() != null && parseDuration(throttle.maxWait()) == null) {
            return problem("throttle.maxWait must be a duration such as 200ms or 1s, but was '"
                    + throttle.maxWait() + "'");
        }
        return Optional.empty();
    }

    private static Optional<String> filters(List<RouteFilter> filters, String identityHeaderPrefix) {
        if (filters == null) {
            return Optional.empty();
        }
        String identityPrefix = identityHeaderPrefix.toLowerCase(Locale.ROOT);
        for (int i = 0; i < filters.size(); i++) {
            RouteFilter filter = filters.get(i);
            String where = "filters[" + i + "]";
            if (filter == null || filter.name() == null || !FILTER_CATALOG.containsKey(filter.name())) {
                return problem(where + ".name must be one of " + FILTER_CATALOG.keySet() + ", but was '"
                        + (filter == null ? null : filter.name()) + "'");
            }
            Map<String, String> args = filter.args() == null ? Map.of() : filter.args();
            for (String required : FILTER_CATALOG.get(filter.name())) {
                if (args.get(required) == null) {
                    return problem(where + " (" + filter.name() + ") needs the argument '" + required + "'");
                }
            }
            for (String arg : args.keySet()) {
                if (!FILTER_CATALOG.get(filter.name()).contains(arg)) {
                    return problem(where + " (" + filter.name() + ") does not take the argument '" + arg
                            + "'; it takes " + FILTER_CATALOG.get(filter.name()));
                }
            }
            Optional<String> problem = filterArgs(where, filter.name(), args, identityPrefix);
            if (problem.isPresent()) {
                return problem;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> filterArgs(String where, String name, Map<String, String> args,
                                               String identityPrefix) {
        String header = args.get("name");
        if (name.endsWith("Header")) {
            if (!HEADER_NAME.matcher(header).matches()) {
                return problem(where + " (" + name + ") has an invalid header name '" + header + "'");
            }
            if (REQUEST_HEADER_FILTERS.contains(name) && header.toLowerCase(Locale.ROOT).startsWith(identityPrefix)) {
                return problem(where + " (" + name + ") must not touch the identity header '" + header
                        + "': only the gateway sets it");
            }
        }
        return switch (name) {
            case "RewritePath" -> {
                try {
                    Pattern.compile(args.get("regexp"));
                    yield Optional.empty();
                } catch (PatternSyntaxException e) {
                    yield problem(where + " (RewritePath) has an invalid regexp: " + e.getDescription());
                }
            }
            case "PrefixPath" -> args.get("prefix").startsWith("/") ? Optional.empty()
                    : problem(where + " (PrefixPath) prefix must start with /");
            case "SetPath" -> args.get("template").startsWith("/") ? Optional.empty()
                    : problem(where + " (SetPath) template must start with /");
            case "RequestSize" -> DATA_SIZE.matcher(args.get("maxSize")).matches() ? Optional.empty()
                    : problem(where + " (RequestSize) maxSize must look like 512KB, 5MB or 1048576");
            default -> Optional.empty();
        };
    }

    private static Optional<String> transform(String where, RouteTransform transform) {
        if (transform == null) {
            return Optional.empty();
        }
        boolean hasAllow = transform.allow() != null && !transform.allow().isEmpty();
        boolean hasDeny = transform.deny() != null && !transform.deny().isEmpty();
        if (hasAllow && hasDeny) {
            return problem(where + " takes allow or deny, not both");
        }
        for (List<String> fields : java.util.Arrays.asList(transform.allow(), transform.deny())) {
            if (fields != null && fields.stream().anyMatch(field -> field == null || field.isBlank())) {
                return problem(where + ".allow and " + where + ".deny must not have blank fields");
            }
        }
        if (transform.mapping() != null) {
            for (Map.Entry<String, String> rename : transform.mapping().entrySet()) {
                if (rename.getKey() == null || rename.getKey().isBlank()
                        || rename.getValue() == null || rename.getValue().isBlank()) {
                    return problem(where + ".mapping must rename a field to a non-blank name");
                }
            }
        }
        if (transform.target() != null && transform.target().isBlank()) {
            return problem(where + ".target must not be blank");
        }
        if (transform.group() != null && transform.group().isBlank()) {
            return problem(where + ".group must not be blank");
        }
        return Optional.empty();
    }

    private static Optional<String> aggregate(RouteAggregate aggregate, List<String> methods) {
        if (aggregate == null) {
            return Optional.empty();
        }
        if (methods != null && methods.stream().anyMatch(method -> !"GET".equals(method))) {
            return problem("aggregate only answers GET; methods must be [GET] or absent");
        }
        List<RouteBackend> backends = aggregate.backends();
        if (backends == null || backends.size() < 2 || backends.size() > MAX_BACKENDS) {
            return problem("aggregate.backends must have between 2 and " + MAX_BACKENDS + " backends");
        }
        if (aggregate.timeout() != null) {
            Duration timeout = parseDuration(aggregate.timeout());
            if (timeout == null || timeout.isZero()) {
                return problem("aggregate.timeout must be a positive duration such as 2s, but was '"
                        + aggregate.timeout() + "'");
            }
        }
        Set<String> groups = new HashSet<>();
        for (int i = 0; i < backends.size(); i++) {
            RouteBackend backend = backends.get(i);
            String where = "aggregate.backends[" + i + "]";
            if (backend == null || !isHttpUrl(backend.url())) {
                return problem(where + ".url must be an absolute http(s) URL, but was '"
                        + (backend == null ? null : backend.url()) + "'");
            }
            if (backend.group() != null && (backend.group().isBlank() || !groups.add(backend.group()))) {
                return problem(where + ".group must be non-blank and unique, but was '" + backend.group() + "'");
            }
            Optional<String> problem = transform(where + ".transform", backend.transform());
            if (problem.isPresent()) {
                return problem;
            }
        }
        return Optional.empty();
    }

    private static boolean isHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(PATH_VARIABLE.matcher(url).replaceAll("x"));
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static Optional<String> problem(String message) {
        return Optional.of(message);
    }
}
