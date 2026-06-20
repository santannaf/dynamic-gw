package com.example.gateway.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webflux.autoconfigure.WebFluxProperties;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.StripPrefixGatewayFilterFactory;
import org.springframework.cloud.gateway.handler.AsyncPredicate;
import org.springframework.cloud.gateway.handler.predicate.MethodRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.web.server.ServerWebExchange;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class FastRouteCompiler implements RouteCompiler {

    private static final Logger log = LoggerFactory.getLogger(FastRouteCompiler.class);

    private static final String PATH_PREDICATE = "Path";
    private static final String METHOD_PREDICATE = "Method";
    private static final String STRIP_PREFIX_FILTER = "StripPrefix";

    private final ThreadLocal<PathRoutePredicateFactory> pathFactoryPerThread;
    private final MethodRoutePredicateFactory methodFactory;
    private final StripPrefixGatewayFilterFactory stripPrefixFactory;
    private final GatewayProperties gatewayProperties;
    private final RouteCompiler fallback;

    public FastRouteCompiler(List<RoutePredicateFactory<?>> predicateFactories,
                             List<GatewayFilterFactory<?>> filterFactories,
                             GatewayProperties gatewayProperties,
                             WebFluxProperties webFluxProperties,
                             RouteCompiler fallback) {
        this.pathFactoryPerThread = ThreadLocal.withInitial(
                () -> new PathRoutePredicateFactory(webFluxProperties));
        pickRequired(predicateFactories, PathRoutePredicateFactory.class);
        this.methodFactory = pickRequired(predicateFactories, MethodRoutePredicateFactory.class);
        this.stripPrefixFactory = pickRequired(filterFactories, StripPrefixGatewayFilterFactory.class);
        this.gatewayProperties = gatewayProperties;
        this.fallback = fallback;
    }

    @Override
    public List<Route> compile(List<RouteDefinition> definitions) {
        if (!gatewayProperties.getDefaultFilters().isEmpty()) {
            log.debug("Default filters configured; delegating all routes to slow compiler");
            return fallback.compile(definitions);
        }

        List<RouteDefinition> fastBatch = new ArrayList<>(definitions.size());
        List<RouteDefinition> slowBatch = null;
        for (RouteDefinition rd : definitions) {
            if (isFastPathCompatible(rd)) {
                fastBatch.add(rd);
            } else {
                if (slowBatch == null) slowBatch = new ArrayList<>();
                slowBatch.add(rd);
            }
        }

        List<Route> fastRoutes = fastBatch.parallelStream()
                .map(this::buildRouteFast)
                .toList();

        // 3) Fallback (serial) para o que sobrou.
        List<Route> slowRoutes;
        if (slowBatch != null && !slowBatch.isEmpty()) {
            log.info("FastRouteCompiler fast-path={} fallback={}",
                    fastRoutes.size(), slowBatch.size());
            slowRoutes = fallback.compile(slowBatch);
        } else {
            slowRoutes = List.of();
        }

        if (slowRoutes.isEmpty()) {
            return fastRoutes;
        }
        List<Route> combined = new ArrayList<>(fastRoutes.size() + slowRoutes.size());
        combined.addAll(fastRoutes);
        combined.addAll(slowRoutes);
        return List.copyOf(combined);
    }

    private boolean isFastPathCompatible(RouteDefinition rd) {
        for (PredicateDefinition p : rd.getPredicates()) {
            String name = p.getName();
            if (!PATH_PREDICATE.equals(name) && !METHOD_PREDICATE.equals(name)) {
                return false;
            }
        }
        for (FilterDefinition f : rd.getFilters()) {
            if (!STRIP_PREFIX_FILTER.equals(f.getName())) {
                return false;
            }
        }
        return true;
    }

    /** Compila um RouteDefinition que já passou por {@link #isFastPathCompatible}. */
    private Route buildRouteFast(RouteDefinition rd) {
        AsyncPredicate<ServerWebExchange> predicate = AsyncPredicate.from(_ -> true);
        for (PredicateDefinition p : rd.getPredicates()) {
            assert p.getName() != null;
            AsyncPredicate<ServerWebExchange> ap = switch (p.getName()) {
                case PATH_PREDICATE -> buildPath(p);
                case METHOD_PREDICATE -> buildMethod(p);
                default -> throw new IllegalStateException("unreachable: " + p.getName());
            };
            predicate = predicate.and(ap);
        }

        List<GatewayFilter> filters = new ArrayList<>();
        int i = 0;
        for (FilterDefinition f : rd.getFilters()) {
            GatewayFilter gf = buildStripPrefix(f);
            filters.add(gf instanceof Ordered ? gf : new OrderedGatewayFilter(gf, i + 1));
            i++;
        }

        return Route.async(rd)
                .asyncPredicate(predicate)
                .replaceFilters(filters)
                .build();
    }

    private AsyncPredicate<ServerWebExchange> buildPath(PredicateDefinition def) {
        PathRoutePredicateFactory.Config cfg = new PathRoutePredicateFactory.Config();
        cfg.setPatterns(orderedArgValues(def.getArgs()));
        // Per-thread factory: zero contenção entre threads do parallelStream.
        return pathFactoryPerThread.get().applyAsync(cfg);
    }

    private AsyncPredicate<ServerWebExchange> buildMethod(PredicateDefinition def) {
        MethodRoutePredicateFactory.Config cfg = new MethodRoutePredicateFactory.Config();
        List<String> raw = orderedArgValues(def.getArgs());
        HttpMethod[] methods = raw.stream()
                .map(s -> HttpMethod.valueOf(s.toUpperCase()))
                .toArray(HttpMethod[]::new);
        cfg.setMethods(methods);
        return methodFactory.applyAsync(cfg);
    }

    private GatewayFilter buildStripPrefix(FilterDefinition def) {
        StripPrefixGatewayFilterFactory.Config cfg = new StripPrefixGatewayFilterFactory.Config();
        List<String> raw = orderedArgValues(def.getArgs());
        cfg.setParts(raw.isEmpty() ? 1 : Integer.parseInt(raw.getFirst()));
        return stripPrefixFactory.apply(cfg);
    }

    private static List<String> orderedArgValues(Map<String, String> args) {
        if (args == null || args.isEmpty()) {
            return List.of();
        }
        return args.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> indexOfGenkey(e.getKey())))
                .map(Map.Entry::getValue)
                .toList();
    }

    private static int indexOfGenkey(String key) {
        int idx = key.lastIndexOf('_');
        if (idx < 0 || idx == key.length() - 1) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(key.substring(idx + 1));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    //noinspection unchecked
    private static <T, F> F pickRequired(List<T> beans, Class<F> type) {
        for (T b : beans) {
            if (type.isInstance(b)) {
                return (F) b;
            }
        }
        throw new IllegalStateException("Required factory bean not found in context: " + type.getName());
    }
}