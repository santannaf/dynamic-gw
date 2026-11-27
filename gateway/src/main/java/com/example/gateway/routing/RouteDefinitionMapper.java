package com.example.gateway.routing;

import com.example.shared.routes.RouteConfigEntry;
import com.example.shared.routes.RouteFilter;
import com.example.shared.routes.RoutePolicyRules;
import com.example.shared.routes.RouteTimeouts;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.support.RouteMetadataUtils;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class RouteDefinitionMapper {

    /** Metadata key holding the route's {@link com.example.shared.routes.RouteAccess}, when declared. */
    public static final String ACCESS_METADATA_KEY = "access";
    /** Metadata keys of the route policies, each holding the shared record as declared. */
    public static final String RATE_LIMIT_METADATA_KEY = "rateLimit";
    public static final String THROTTLE_METADATA_KEY = "throttle";
    public static final String TRANSFORM_METADATA_KEY = "transform";
    public static final String AGGREGATE_METADATA_KEY = "aggregate";
    public static final String TIMEOUTS_METADATA_KEY = "timeouts";

    // An aggregated route has no single upstream: AggregationFilter answers it
    // and the routing filters skip the no://op scheme.
    private static final URI NO_UPSTREAM = URI.create("no://op");
    private static final int DEFAULT_STRIP_PREFIX = 1;

    public RouteDefinition toDefinition(RouteConfigEntry entry) {
        RouteDefinition rd = new RouteDefinition();
        rd.setId(entry.id());
        rd.setUri(entry.aggregate() != null ? NO_UPSTREAM : URI.create(entry.targetUri()));

        List<PredicateDefinition> predicates = new ArrayList<>(2);
        predicates.add(pathPredicate(entry.path()));
        List<String> methods = entry.methods();
        if (entry.aggregate() != null && (methods == null || methods.isEmpty())) {
            methods = List.of("GET");
        }
        if (methods != null && !methods.isEmpty()) {
            predicates.add(methodPredicate(methods));
        }
        rd.setPredicates(predicates);

        int strip = entry.stripPrefix() == null ? DEFAULT_STRIP_PREFIX : entry.stripPrefix();
        List<FilterDefinition> filters = new ArrayList<>(1 + (entry.filters() == null ? 0 : entry.filters().size()));
        filters.add(stripPrefixFilter(strip));
        // Catalog filters run after StripPrefix, in the declared order. Any of
        // them takes the route off FastRouteCompiler's fast path.
        if (entry.filters() != null) {
            for (RouteFilter filter : entry.filters()) {
                filters.add(catalogFilter(filter));
            }
        }
        rd.setFilters(filters);

        // Stash ownership/description metadata on the RouteDefinition itself.
        // Spring Cloud Gateway always allocates a metadata Map per Route, so
        // piggy-backing on it adds zero extra fields to the locator. Only put
        // non-null values to keep the JSON of /internal/routes lean.
        if (entry.team() != null) {
            rd.getMetadata().put("team", entry.team());
        }
        if (entry.description() != null) {
            rd.getMetadata().put("description", entry.description());
        }
        // The access policy and the traffic policies travel as metadata, never
        // as filters: a filter other than StripPrefix would push the route off
        // FastRouteCompiler's fast path. Global filters read them per request.
        if (entry.access() != null) {
            rd.getMetadata().put(ACCESS_METADATA_KEY, entry.access());
        }
        if (entry.rateLimit() != null) {
            rd.getMetadata().put(RATE_LIMIT_METADATA_KEY, entry.rateLimit());
        }
        if (entry.throttle() != null) {
            rd.getMetadata().put(THROTTLE_METADATA_KEY, entry.throttle());
        }
        if (entry.transform() != null) {
            rd.getMetadata().put(TRANSFORM_METADATA_KEY, entry.transform());
        }
        if (entry.aggregate() != null) {
            rd.getMetadata().put(AGGREGATE_METADATA_KEY, entry.aggregate());
        }
        if (entry.timeouts() != null) {
            rd.getMetadata().put(TIMEOUTS_METADATA_KEY, entry.timeouts());
            putUpstreamTimeouts(rd.getMetadata(), entry.timeouts());
        }

        return rd;
    }

    // NettyRoutingFilter reads these keys per route, in milliseconds. A timeout the
    // route leaves out stays out of the metadata, so the global value of
    // spring.cloud.gateway.server.webflux.httpclient applies to it.
    private static void putUpstreamTimeouts(Map<String, Object> metadata, RouteTimeouts timeouts) {
        Duration connect = RoutePolicyRules.parseDuration(timeouts.connect());
        if (connect != null) {
            metadata.put(RouteMetadataUtils.CONNECT_TIMEOUT_ATTR, Math.toIntExact(connect.toMillis()));
        }
        Duration response = RoutePolicyRules.parseDuration(timeouts.response());
        if (response != null) {
            metadata.put(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR, response.toMillis());
        }
    }

    private static PredicateDefinition pathPredicate(String path) {
        PredicateDefinition predicate = new PredicateDefinition();
        predicate.setName("Path");
        predicate.addArg("_genkey_0", path);
        return predicate;
    }

    private static PredicateDefinition methodPredicate(List<String> methods) {
        PredicateDefinition predicate = new PredicateDefinition();
        predicate.setName("Method");
        for (int i = 0; i < methods.size(); i++) {
            predicate.addArg("_genkey_" + i, methods.get(i));
        }
        return predicate;
    }

    private static FilterDefinition stripPrefixFilter(int parts) {
        FilterDefinition filter = new FilterDefinition();
        filter.setName("StripPrefix");
        filter.addArg("_genkey_0", String.valueOf(parts));
        return filter;
    }

    // Named arguments (name, value, regexp, ...) bind straight to the factory's Config.
    private static FilterDefinition catalogFilter(RouteFilter routeFilter) {
        FilterDefinition filter = new FilterDefinition();
        filter.setName(routeFilter.name());
        if (routeFilter.args() != null) {
            routeFilter.args().forEach(filter::addArg);
        }
        return filter;
    }
}
