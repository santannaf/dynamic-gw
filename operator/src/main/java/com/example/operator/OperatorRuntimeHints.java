package com.example.operator;

import com.example.operator.crd.GatewayRoute;
import com.example.operator.crd.GatewayRouteAccess;
import com.example.operator.crd.GatewayRouteList;
import com.example.operator.crd.GatewayRouteSpec;
import com.example.shared.routes.RouteAccess;
import com.example.shared.routes.RouteAggregate;
import com.example.shared.routes.RouteBackend;
import com.example.shared.routes.RouteConfigEntry;
import com.example.shared.routes.RouteConfigSnapshot;
import com.example.shared.routes.RouteFilter;
import com.example.shared.routes.RouteRateLimit;
import com.example.shared.routes.RouteThrottle;
import com.example.shared.routes.RouteTransform;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

public class OperatorRuntimeHints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        var reflection = hints.reflection();
        for (Class<?> cls : new Class<?>[]{
                RouteConfigSnapshot.class,
                RouteConfigEntry.class,
                RouteAccess.class,
                RouteRateLimit.class,
                RouteThrottle.class,
                RouteFilter.class,
                RouteTransform.class,
                RouteAggregate.class,
                RouteBackend.class,
                GatewayRoute.class,
                GatewayRouteSpec.class,
                GatewayRouteAccess.class,
                GatewayRouteList.class
        }) {
            reflection.registerType(cls,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.INVOKE_PUBLIC_METHODS,
                    MemberCategory.ACCESS_DECLARED_FIELDS,
                    MemberCategory.ACCESS_PUBLIC_FIELDS);
        }
    }
}
