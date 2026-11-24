package com.example.gateway.traffic;

import com.example.shared.routes.RouteTransform;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonTransformerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PEDIDO = """
            {"data": {"pedido": {"id": 7, "nome": "Mesa", "senha": "x", "itens": [1, 2]}}, "meta": {"page": 1}}""";

    @Test
    void targetThenAllowThenMappingThenGroup() {
        RouteTransform transform = new RouteTransform(List.of("id", "nome"), null, Map.of("nome", "name"),
                "data.pedido", "order");

        assertThat(apply(PEDIDO, transform)).isEqualTo("{\"order\":{\"id\":7,\"name\":\"Mesa\"}}");
    }

    @Test
    void denyDropsOnlyTheListedFields() {
        RouteTransform transform = new RouteTransform(null, List.of("senha", "itens"), null, "data.pedido", null);

        assertThat(apply(PEDIDO, transform)).isEqualTo("{\"id\":7,\"nome\":\"Mesa\"}");
    }

    @Test
    void fieldRulesApplyToEachObjectOfAList() {
        RouteTransform transform = new RouteTransform(List.of("id"), null, Map.of("id", "codigo"), null, "itens");

        assertThat(apply("[{\"id\":1,\"x\":0},{\"id\":2},3]", transform))
                .isEqualTo("{\"itens\":[{\"codigo\":1},{\"codigo\":2},3]}");
    }

    @Test
    void missingTargetYieldsAnEmptyObject() {
        assertThat(apply(PEDIDO, new RouteTransform(null, null, null, "data.nada", null))).isEqualTo("{}");
        assertThat(apply("[1]", new RouteTransform(null, null, null, "data", null))).isEqualTo("{}");
    }

    @Test
    void groupAloneWrapsTheWholeDocument() {
        assertThat(apply("{\"a\":1}", new RouteTransform(null, null, null, null, "eco"))).isEqualTo("{\"eco\":{\"a\":1}}");
    }

    private static String apply(String json, RouteTransform transform) {
        JsonNode result = JsonTransformer.apply(JSON.readTree(json), transform);
        return JSON.writeValueAsString(result);
    }
}
