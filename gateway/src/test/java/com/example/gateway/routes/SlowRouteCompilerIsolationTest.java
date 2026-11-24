package com.example.gateway.routes;

import com.example.gateway.GatewayRuntimeHints;
import com.example.gateway.routing.RouteCompiler;
import com.example.gateway.routing.RouteDefinitionMapper;
import com.example.shared.routes.RouteConfigEntry;
import com.example.shared.routes.RouteFilter;
import com.example.shared.routes.RoutePolicyRules;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.core.ResolvableType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "gateway.routes.store.type=properties",
        "gateway.security.enabled=false"
})
class SlowRouteCompilerIsolationTest {

    @Autowired
    @Qualifier("slowRouteCompiler")
    RouteCompiler slowCompiler;

    @Autowired
    List<GatewayFilterFactory<?>> filterFactories;

    private final RouteDefinitionMapper mapper = new RouteDefinitionMapper();

    @Test
    void aRouteThatDoesNotCompileIsLeftOutAlone() {
        RouteDefinition good = mapper.toDefinition(new RouteConfigEntry("good", "/g/**", "http://g", 1, null, true,
                null, null, null, null, null,
                List.of(new RouteFilter("AddRequestHeader", Map.of("name", "X-A", "value", "1"))), null, null));
        RouteDefinition broken = mapper.toDefinition(new RouteConfigEntry("broken", "/b/**", "http://b", 1, null,
                true, null, null, null, null, null, null, null, null));
        FilterDefinition unknown = new FilterDefinition();
        unknown.setName("NoSuchFilter");
        broken.getFilters().add(unknown);

        List<Route> routes = slowCompiler.compile(List.of(broken, good));

        assertThat(routes).extracting(Route::getId).containsExactly("good");
    }

    @Test
    void everyCatalogFilterHasItsConfigReadyForTheNativeImage() {
        RuntimeHints hints = new RuntimeHints();
        new GatewayRuntimeHints().registerHints(hints, getClass().getClassLoader());

        for (String name : RoutePolicyRules.FILTER_CATALOG.keySet()) {
            GatewayFilterFactory<?> factory = filterFactories.stream()
                    .filter(candidate -> name.equals(candidate.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no factory for catalog filter " + name));
            Class<?> config = ResolvableType.forClass(factory.getClass()).as(GatewayFilterFactory.class)
                    .getGeneric(0).resolve();
            Class<?> created = factory.newConfig().getClass();
            assertThat(RuntimeHintsPredicates.reflection().onType(created).test(hints))
                    .as("%s binds %s (declared %s)", name, created.getName(), config).isTrue();
        }
    }
}
