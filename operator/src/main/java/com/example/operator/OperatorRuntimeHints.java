package com.example.operator;

import com.example.operator.crd.GatewayRoute;
import com.example.operator.crd.GatewayRouteList;
import com.example.operator.crd.GatewayRouteSpec;
import com.example.shared.routes.RouteConfigEntry;
import com.example.shared.routes.RouteConfigSnapshot;
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
                GatewayRoute.class,
                GatewayRouteSpec.class,
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
