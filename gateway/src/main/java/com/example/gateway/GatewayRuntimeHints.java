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
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

public class GatewayRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // The favicon the gateway answers when no route serves one (Spring Boot's static resources).
        hints.resources().registerPattern("static/favicon.ico");
        // Spring Boot only embeds the application*.yaml at the classpath root; the profile
        // files live in folders and application.yaml imports them.
        for (String folder : new String[]{"keycloak", "keycloak-ldap", "ldap", "ldap-directory", "oauth", "s3",
                "properties"}) {
            hints.resources().registerPattern(folder + "/*.yaml");
        }
        hints.reflection()
                .registerType(RouteConfigSnapshot.class,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.INVOKE_DECLARED_METHODS,
                        MemberCategory.ACCESS_DECLARED_FIELDS)
                .registerType(RouteConfigEntry.class,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.INVOKE_DECLARED_METHODS,
                        MemberCategory.ACCESS_DECLARED_FIELDS)
                .registerType(RouteAccess.class,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.INVOKE_DECLARED_METHODS,
                        MemberCategory.ACCESS_DECLARED_FIELDS)
                .registerType(RouteObo.class,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.INVOKE_DECLARED_METHODS,
                        MemberCategory.ACCESS_DECLARED_FIELDS);
        // Catalog filters (RoutePolicyRules.FILTER_CATALOG) compile on Spring Cloud
        // Gateway's slow path, which binds their Config by reflection; its own hints
        // do not cover these classes.
        for (String config : new String[]{
                "org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory$NameConfig",
                "org.springframework.cloud.gateway.filter.factory.AbstractNameValueGatewayFilterFactory$NameValueConfig",
                "org.springframework.cloud.gateway.filter.factory.AddResponseHeaderGatewayFilterFactory$Config",
                "org.springframework.cloud.gateway.filter.factory.PrefixPathGatewayFilterFactory$Config",
                "org.springframework.cloud.gateway.filter.factory.RequestSizeGatewayFilterFactory$RequestSizeConfig",
                "org.springframework.cloud.gateway.filter.factory.RewritePathGatewayFilterFactory$Config",
                "org.springframework.cloud.gateway.filter.factory.SetPathGatewayFilterFactory$Config"}) {
            hints.reflection().registerType(TypeReference.of(config),
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.INVOKE_PUBLIC_METHODS,
                    MemberCategory.ACCESS_DECLARED_FIELDS);
        }
        // Policies: read from the snapshot (Jackson), bound from properties in
        // v1 (Binder), and written by /internal/routes and the reload hash.
        for (Class<?> policy : new Class<?>[]{RouteRateLimit.class, RouteThrottle.class, RouteFilter.class,
                RouteTransform.class, RouteAggregate.class, RouteBackend.class, RouteTimeouts.class}) {
            hints.reflection().registerType(policy,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.ACCESS_DECLARED_FIELDS);
        }
    }
}
