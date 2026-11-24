package com.example.gateway.routing;

import com.example.shared.routes.RouteAggregate;
import com.example.shared.routes.RouteBackend;
import com.example.shared.routes.RouteConfigEntry;
import com.example.shared.routes.RouteFilter;
import com.example.shared.routes.RouteRateLimit;
import com.example.shared.routes.RouteThrottle;
import com.example.shared.routes.RouteTransform;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RouteDefinitionMapperPoliciesTest {

    private final RouteDefinitionMapper mapper = new RouteDefinitionMapper();

    @Test
    void policiesTravelAsMetadataWithoutExtraFilters() {
        RouteRateLimit rateLimit = new RouteRateLimit(10, "1s", null, null);
        RouteThrottle throttle = new RouteThrottle(5, 10, "100ms");
        RouteTransform transform = new RouteTransform(List.of("id"), null, null, null, null);

        RouteDefinition rd = mapper.toDefinition(new RouteConfigEntry("a", "/a/**", "http://a", 1, null, true, null, null,
                null, rateLimit, throttle, null, transform, null));

        assertThat(rd.getMetadata())
                .containsEntry(RouteDefinitionMapper.RATE_LIMIT_METADATA_KEY, rateLimit)
                .containsEntry(RouteDefinitionMapper.THROTTLE_METADATA_KEY, throttle)
                .containsEntry(RouteDefinitionMapper.TRANSFORM_METADATA_KEY, transform)
                .doesNotContainKey(RouteDefinitionMapper.AGGREGATE_METADATA_KEY);
        assertThat(rd.getFilters()).extracting(FilterDefinition::getName).containsExactly("StripPrefix");
    }

    @Test
    void catalogFiltersFollowStripPrefixInDeclaredOrderWithNamedArgs() {
        Map<String, String> rewrite = new LinkedHashMap<>();
        rewrite.put("regexp", "/a/(?<rest>.*)");
        rewrite.put("replacement", "/v2/${rest}");

        RouteDefinition rd = mapper.toDefinition(new RouteConfigEntry("a", "/a/**", "http://a", 2, null, true, null,
                null, null, null, null, List.of(
                        new RouteFilter("AddRequestHeader", Map.of("name", "X-Source", "value", "gateway")),
                        new RouteFilter("RewritePath", rewrite)), null, null));

        assertThat(rd.getFilters()).extracting(FilterDefinition::getName)
                .containsExactly("StripPrefix", "AddRequestHeader", "RewritePath");
        assertThat(rd.getFilters().get(1).getArgs()).containsEntry("name", "X-Source").containsEntry("value", "gateway");
        assertThat(rd.getFilters().get(2).getArgs()).containsExactlyEntriesOf(rewrite);
    }

    @Test
    void aggregatedRouteHasNoUpstreamAndAnswersGetOnly() {
        RouteAggregate aggregate = new RouteAggregate(List.of(
                new RouteBackend("http://p/{id}", "pedido", null), new RouteBackend("http://c/{id}", "cliente", null)), null);

        RouteDefinition rd = mapper.toDefinition(new RouteConfigEntry("painel", "/painel/{id}", null, 1, null, true, null,
                null, null, null, null, null, null, aggregate));

        assertThat(rd.getUri()).hasToString("no://op");
        assertThat(rd.getMetadata()).containsEntry(RouteDefinitionMapper.AGGREGATE_METADATA_KEY, aggregate);
        assertThat(rd.getPredicates()).extracting(PredicateDefinition::getName).containsExactly("Path", "Method");
        assertThat(rd.getPredicates().get(1).getArgs()).containsValue("GET");
    }
}
