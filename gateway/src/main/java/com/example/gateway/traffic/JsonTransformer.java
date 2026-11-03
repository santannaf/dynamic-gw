package com.example.gateway.traffic;

import com.example.shared.routes.RouteTransform;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Collection;
import java.util.Map;

/**
 * Applies a {@link RouteTransform} to a JSON document, in KrakenD's order:
 * {@code target} (a dot path, such as {@code data.pedido}), then {@code allow}
 * or {@code deny}, then {@code mapping}, then {@code group}. Field rules apply
 * to the top-level fields of an object, or of each object in an array.
 */
final class JsonTransformer {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private JsonTransformer() {
    }

    static JsonNode apply(JsonNode document, RouteTransform transform) {
        JsonNode result = transform.target() == null ? document : at(document, transform.target());
        result = fields(result, transform);
        if (transform.group() != null) {
            ObjectNode wrapper = NODES.objectNode();
            wrapper.set(transform.group(), result);
            result = wrapper;
        }
        return result;
    }

    // A missing target yields an empty object, as KrakenD does.
    private static JsonNode at(JsonNode document, String path) {
        JsonNode current = document;
        for (String segment : path.split("\\.")) {
            current = current != null && current.isObject() ? current.get(segment) : null;
        }
        return current == null ? NODES.objectNode() : current;
    }

    private static JsonNode fields(JsonNode node, RouteTransform transform) {
        if (node.isArray()) {
            ArrayNode out = NODES.arrayNode();
            for (JsonNode element : node) {
                out.add(fields(element, transform));
            }
            return out;
        }
        boolean hasAllow = notEmpty(transform.allow());
        boolean hasDeny = notEmpty(transform.deny());
        Map<String, String> mapping = transform.mapping();
        if (!node.isObject() || (!hasAllow && !hasDeny && (mapping == null || mapping.isEmpty()))) {
            return node;
        }
        ObjectNode out = NODES.objectNode();
        for (Map.Entry<String, JsonNode> property : node.properties()) {
            String name = property.getKey();
            if ((hasAllow && !transform.allow().contains(name)) || (hasDeny && transform.deny().contains(name))) {
                continue;
            }
            out.set(mapping == null ? name : mapping.getOrDefault(name, name), property.getValue());
        }
        return out;
    }

    private static boolean notEmpty(Collection<?> values) {
        return values != null && !values.isEmpty();
    }
}
