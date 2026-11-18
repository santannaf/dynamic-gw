package com.example.operator;

import com.example.operator.crd.GatewayRouteAccess;
import com.example.shared.routes.RouteAccess;
import com.example.shared.routes.RouteObo;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OperatorRuntimeHintsTest {

    @Test
    void accessTypesAreRegisteredForReflection() {
        RuntimeHints hints = new RuntimeHints();
        new OperatorRuntimeHints().registerHints(hints, getClass().getClassLoader());

        for (Class<?> type : List.of(RouteAccess.class, RouteObo.class, GatewayRouteAccess.class)) {
            assertThat(RuntimeHintsPredicates.reflection().onType(type).test(hints)).as(type.getSimpleName()).isTrue();
        }
    }
}
