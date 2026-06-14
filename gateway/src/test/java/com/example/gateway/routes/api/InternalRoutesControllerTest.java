package com.example.gateway.routes.api;

import com.example.gateway.routes.GatewayRouteReloadService;
import com.example.gateway.routing.InMemoryDynamicRouteDefinitionRepository;
import com.example.gateway.routing.RouteDefinitionMapper;
import com.example.shared.routes.RouteConfigEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.RouteDefinitionRepository;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalRoutesControllerTest {

    private InMemoryDynamicRouteDefinitionRepository repository;
    private RouteDefinitionMapper mapper;

    @BeforeEach
    void setUp() {
        repository = new InMemoryDynamicRouteDefinitionRepository();
        mapper = new RouteDefinitionMapper();
    }

    @Test
    void postReloadReturnsActiveRouteIds() {
        GatewayRouteReloadService service = mock(GatewayRouteReloadService.class);
        when(service.reloadFromStore()).thenReturn(List.of("a", "b"));
        InternalRoutesController controller = new InternalRoutesController(service, repository);

        WebTestClient client = WebTestClient.bindToController(controller).build();

        client.post().uri("/internal/routes/reload")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("reloaded")
                .jsonPath("$.routes[0]").isEqualTo("a")
                .jsonPath("$.routes[1]").isEqualTo("b");
    }

    @Test
    void postReloadReturns500OnFailure() {
        GatewayRouteReloadService service = mock(GatewayRouteReloadService.class);
        when(service.reloadFromStore()).thenThrow(new RuntimeException("provider down"));
        InternalRoutesController controller = new InternalRoutesController(service, repository);

        WebTestClient client = WebTestClient.bindToController(controller).build();

        client.post().uri("/internal/routes/reload")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.status").isEqualTo("error")
                .jsonPath("$.error").isEqualTo("provider down");
    }

    @Test
    void getListsCurrentRoutesAndDoesNotReload() {
        repository.replaceAll(List.of(
                mapper.toDefinition(new RouteConfigEntry("httpbin-route", "/httpbin/**", "http://httpbin.org", 1, List.of("GET", "POST"), true))
        ));
        GatewayRouteReloadService service = mock(GatewayRouteReloadService.class);
        InternalRoutesController controller = new InternalRoutesController(service, repository);

        WebTestClient client = WebTestClient.bindToController(controller).build();

        client.get().uri("/internal/routes")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.routes[0].id").isEqualTo("httpbin-route")
                .jsonPath("$.routes[0].uri").isEqualTo("http://httpbin.org")
                .jsonPath("$.routes[0].predicates[0]").isEqualTo("Path=/httpbin/**")
                .jsonPath("$.routes[0].predicates[1]").isEqualTo("Method=GET,POST")
                .jsonPath("$.routes[0].filters[0]").isEqualTo("StripPrefix=1");

        verify(service, never()).reloadFromStore();
    }

    @Test
    void getOnEmptyRepositoryReturnsEmptyList() {
        GatewayRouteReloadService service = mock(GatewayRouteReloadService.class);
        InternalRoutesController controller = new InternalRoutesController(service, repository);

        WebTestClient client = WebTestClient.bindToController(controller).build();

        AtomicInteger calls = new AtomicInteger();
        client.get().uri("/internal/routes")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.routes").isArray()
                .jsonPath("$.routes.length()").isEqualTo(0);

        assertThat(calls).hasValue(0);
    }
}
