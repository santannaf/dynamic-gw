package com.example.operator.crd;

import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A CRD can reach the cluster before the operator that knows its new fields.
 * An unknown field must be ignored: failing to read it stops the informer's
 * watch, and the operator then misses every later change.
 */
class GatewayRouteForwardCompatibilityTest {

    private final KubernetesSerialization serialization = new KubernetesSerialization();

    @Test
    void unknownFieldsInTheSpecAndInPoliciesAreIgnored() {
        String json = """
                {"apiVersion": "platform.saca.pags/v1alpha1", "kind": "GatewayRoute",
                 "metadata": {"name": "future", "namespace": "platform"},
                 "spec": {"path": "/f/**", "targetUri": "http://f", "cache": {"ttl": "1m"},
                          "rateLimit": {"requests": 5, "period": "1s", "algorithm": "sliding-window"},
                          "access": {"mode": "public", "audience": "x"}}}""";

        GatewayRoute route = serialization.unmarshal(json, GatewayRoute.class);

        assertThat(route.getSpec().getPath()).isEqualTo("/f/**");
        assertThat(route.getSpec().getRateLimit().requests()).isEqualTo(5);
        assertThat(route.getSpec().getAccess().getMode()).isEqualTo("public");
    }
}
