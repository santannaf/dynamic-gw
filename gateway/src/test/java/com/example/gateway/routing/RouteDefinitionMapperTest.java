package com.example.gateway.routing;

import com.example.shared.routes.RouteAccess;
import com.example.shared.routes.RouteConfigEntry;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RouteDefinitionMapperTest {

    private final RouteDefinitionMapper mapper = new RouteDefinitionMapper();

    @Test
    void minimalEntryProducesPathPredicateAndDefaultStripPrefix() {
        RouteConfigEntry entry = new RouteConfigEntry(
                "minimal",
                "/foo/**",
                "http://upstream",
                null,
                null,
                true,
                null,
                null,
                null
        );

        RouteDefinition rd = mapper.toDefinition(entry);

        assertThat(rd.getId()).isEqualTo("minimal");
        assertThat(rd.getUri()).hasToString("http://upstream");
        assertThat(rd.getPredicates()).hasSize(1);
        PredicateDefinition path = rd.getPredicates().get(0);
        assertThat(path.getName()).isEqualTo("Path");
        assertThat(path.getArgs()).containsValue("/foo/**");
        assertThat(rd.getFilters()).hasSize(1);
        FilterDefinition strip = rd.getFilters().get(0);
        assertThat(strip.getName()).isEqualTo("StripPrefix");
        assertThat(strip.getArgs()).containsValue("1");
    }

    @Test
    void methodsProduceMethodPredicate() {
        RouteConfigEntry entry = new RouteConfigEntry(
                "withMethods",
                "/api/**",
                "http://upstream",
                1,
                List.of("GET", "POST"),
                true,
                null,
                null,
                null
        );

        RouteDefinition rd = mapper.toDefinition(entry);

        assertThat(rd.getPredicates()).hasSize(2);
        PredicateDefinition method = rd.getPredicates().get(1);
        assertThat(method.getName()).isEqualTo("Method");
        assertThat(method.getArgs().values()).containsExactlyInAnyOrder("GET", "POST");
    }

    @Test
    void explicitStripPrefixIsHonored() {
        RouteConfigEntry entry = new RouteConfigEntry(
                "stripTwo",
                "/v1/api/**",
                "http://upstream",
                2,
                null,
                true,
                null,
                null,
                null
        );

        RouteDefinition rd = mapper.toDefinition(entry);

        assertThat(rd.getFilters()).hasSize(1);
        assertThat(rd.getFilters().get(0).getArgs()).containsValue("2");
    }

    @Test
    void emptyMethodsListSkipsMethodPredicate() {
        RouteConfigEntry entry = new RouteConfigEntry(
                "noMethodPredicate",
                "/api/**",
                "http://upstream",
                1,
                List.of(),
                true,
                null,
                null,
                null
        );

        RouteDefinition rd = mapper.toDefinition(entry);

        assertThat(rd.getPredicates()).hasSize(1);
        assertThat(rd.getPredicates().get(0).getName()).isEqualTo("Path");
    }

    @Test
    void accessTravelsAsMetadataOnly() {
        RouteAccess access = new RouteAccess("authenticated", List.of("group:tools-httpbin"), null);
        RouteDefinition rd = mapper.toDefinition(new RouteConfigEntry(
                "restricted", "/foo/**", "http://upstream", null, null, true, null, null, access));

        assertThat(rd.getMetadata()).containsEntry(RouteDefinitionMapper.ACCESS_METADATA_KEY, access);
        assertThat(rd.getFilters()).extracting(FilterDefinition::getName).containsExactly("StripPrefix");
    }

    @Test
    void entryWithoutAccessHasNoAccessMetadata() {
        RouteDefinition rd = mapper.toDefinition(new RouteConfigEntry(
                "open", "/foo/**", "http://upstream", null, null, true, null, null, null));

        assertThat(rd.getMetadata()).doesNotContainKey(RouteDefinitionMapper.ACCESS_METADATA_KEY);
    }
}
