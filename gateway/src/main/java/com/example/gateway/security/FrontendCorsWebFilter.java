package com.example.gateway.security;

import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.time.Duration;
import java.util.List;

/**
 * CORS for fronts served from another origin, such as a static site on a CDN
 * calling its BFF through the gateway. A preflight carries no credentials, so
 * it is answered here, before the route filter could turn it into a 401.
 * Runs inside the audit filter (order -200), so a refused origin is audited,
 * and before Spring Security (order -100). {@code /internal/**} stays out: no
 * front calls it.
 */
public class FrontendCorsWebFilter extends CorsWebFilter implements Ordered {

    public static final int ORDER = -150;

    private static final PathPattern INTERNAL = PathPatternParser.defaultInstance.parse("/internal/**");

    public FrontendCorsWebFilter(List<String> origins) {
        super(source(origins));
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    private static CorsConfigurationSource source(List<String> origins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"));
        configuration.addAllowedHeader(CorsConfiguration.ALL);
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(Duration.ofMinutes(10));
        return exchange -> INTERNAL.matches(exchange.getRequest().getPath().pathWithinApplication()) ? null : configuration;
    }
}
