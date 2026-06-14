package com.example.operator;

import com.example.operator.crd.GatewayRoute;
import com.example.operator.crd.GatewayRouteList;
import com.example.operator.crd.GatewayRouteSpec;
import com.example.shared.routes.RouteConfigEntry;
import com.example.shared.routes.RouteConfigSnapshot;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Reflection hints para GraalVM native image:
 *
 * <ul>
 *   <li>{@link RouteConfigSnapshot} / {@link RouteConfigEntry} — Jackson 3 desserializa
 *       o snapshot publicado no ConfigMap; sem hints, o native build perde os componentes
 *       dos records.</li>
 *   <li>{@link GatewayRoute} / {@link GatewayRouteSpec} / {@link GatewayRouteList} —
 *       Fabric8 desserializa o JSON do API server do Kubernetes para essas classes.
 *       Sem hints o informer não consegue materializar os recursos.</li>
 * </ul>
 */
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
