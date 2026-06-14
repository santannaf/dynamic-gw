package com.example.gateway.routing;

import com.example.shared.routes.RouteConfigEntry;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Component
public class RouteDefinitionMapper {

    private static final int DEFAULT_STRIP_PREFIX = 1;

    public RouteDefinition toDefinition(RouteConfigEntry entry) {
        RouteDefinition rd = new RouteDefinition();
        rd.setId(entry.id());
        rd.setUri(URI.create(entry.targetUri()));

        List<PredicateDefinition> predicates = new ArrayList<>(2);
        predicates.add(pathPredicate(entry.path()));
        if (entry.methods() != null && !entry.methods().isEmpty()) {
            predicates.add(methodPredicate(entry.methods()));
        }
        rd.setPredicates(predicates);

        int strip = entry.stripPrefix() == null ? DEFAULT_STRIP_PREFIX : entry.stripPrefix();
        rd.setFilters(List.of(stripPrefixFilter(strip)));

        return rd;
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
}
