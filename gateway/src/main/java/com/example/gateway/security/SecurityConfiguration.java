package com.example.gateway.security;

import com.example.gateway.access.RouteAccessFilter;
import com.example.gateway.routing.InMemoryDynamicRouteLocator;
import com.example.gateway.routing.RouteDefinitionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.WebFilter;

import java.time.Clock;

/**
 * Every bean here exists in every mode; what they do is decided at startup by
 * {@link ActiveSecurity} (see there why not {@code @ConditionalOnProperty}).
 * With {@code gateway.security.enabled=false} the chain permits everything and
 * the filters pass requests through untouched.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewaySecurityProperties.class)
public class SecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

    @Bean
    Clock securityClock() {
        return Clock.systemUTC();
    }

    @Bean
    ActiveSecurity activeSecurity(GatewaySecurityProperties properties, Clock securityClock) {
        return ActiveSecurity.from(properties, securityClock);
    }

    @Bean
    SecurityWebFilterChain gatewaySecurityWebFilterChain(ServerHttpSecurity http, ActiveSecurity activeSecurity) {
        return activeSecurity.buildChain(http);
    }

    @Bean
    GlobalFilter routeAccessFilter(ActiveSecurity activeSecurity) {
        if (!activeSecurity.enabled()) {
            return (exchange, chain) -> chain.filter(exchange);
        }
        return new RouteAccessFilter(activeSecurity.accessSettings(),
                new CookieLoginRedirector(activeSecurity.requestCache(), SecurityChains.loginPath(activeSecurity.properties())));
    }

    @Bean
    WebFilter identityCaptureWebFilter(ActiveSecurity activeSecurity) {
        if (!activeSecurity.enabled()) {
            return (exchange, chain) -> chain.filter(exchange);
        }
        return new IdentityCaptureWebFilter();
    }

    @Bean
    ApplicationListener<RefreshRoutesEvent> undeclaredEnforcementWarning(ActiveSecurity activeSecurity,
                                                                        InMemoryDynamicRouteLocator locator) {
        return event -> {
            if (activeSecurity.enabled()) {
                return;
            }
            long withAccess = locator.currentDefinitions().stream()
                    .filter(rd -> rd.getMetadata().containsKey(RouteDefinitionMapper.ACCESS_METADATA_KEY))
                    .count();
            if (withAccess > 0) {
                log.warn("{} route(s) declare access but gateway.security.enabled=false: "
                        + "route access policies are NOT enforced", withAccess);
            }
        };
    }

    @Bean
    ApplicationRunner auditDisabledWarning(ActiveSecurity activeSecurity, Environment environment) {
        return args -> {
            if (activeSecurity.enabled() && !environment.getProperty("gateway.audit.enabled", Boolean.class, false)) {
                log.warn("gateway.security.enabled=true but gateway.audit.enabled=false: "
                        + "access decisions are enforced but not audited");
            }
        };
    }
}
