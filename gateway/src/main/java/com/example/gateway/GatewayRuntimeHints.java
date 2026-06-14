package com.example.gateway;

import com.example.shared.routes.RouteConfigEntry;
import com.example.shared.routes.RouteConfigSnapshot;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Reflection hints para GraalVM native image: Jackson precisa acessar os componentes
 * dos records via reflexão para desserializar o snapshot lido do ConfigMap.
 * Sem estes hints, {@code ConfigMapRouteConfigProvider.load()} falha no nativo com
 * "Cannot construct instance of RouteConfigSnapshot, no Creators".
 */
public class GatewayRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.reflection()
                .registerType(RouteConfigSnapshot.class,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.INVOKE_DECLARED_METHODS,
                        MemberCategory.ACCESS_DECLARED_FIELDS)
                .registerType(RouteConfigEntry.class,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.INVOKE_DECLARED_METHODS,
                        MemberCategory.ACCESS_DECLARED_FIELDS);
    }
}
