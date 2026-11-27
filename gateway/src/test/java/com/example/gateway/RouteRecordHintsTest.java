package com.example.gateway;

import com.example.shared.routes.RouteAccess;
import com.example.shared.routes.RouteAggregate;
import com.example.shared.routes.RouteBackend;
import com.example.shared.routes.RouteConfigEntry;
import com.example.shared.routes.RouteConfigSnapshot;
import com.example.shared.routes.RouteFilter;
import com.example.shared.routes.RouteObo;
import com.example.shared.routes.RouteRateLimit;
import com.example.shared.routes.RouteThrottle;
import com.example.shared.routes.RouteTimeouts;
import com.example.shared.routes.RouteTransform;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The native image reads the snapshot (Jackson) and binds v1 routes (Binder) by
 * reflection: a route record missing here is silently empty only in the binary.
 */
class RouteRecordHintsTest {

    @Test
    void everyRouteRecordIsRegisteredForReflection() {
        RuntimeHints hints = new RuntimeHints();
        new GatewayRuntimeHints().registerHints(hints, getClass().getClassLoader());

        for (Class<?> type : List.of(RouteConfigSnapshot.class, RouteConfigEntry.class, RouteAccess.class,
                RouteObo.class, RouteRateLimit.class, RouteThrottle.class, RouteFilter.class, RouteTransform.class,
                RouteAggregate.class, RouteBackend.class, RouteTimeouts.class)) {
            assertThat(RuntimeHintsPredicates.reflection().onType(type).test(hints)).as(type.getSimpleName()).isTrue();
        }
    }
}
