package com.example.gateway.audit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.WebFilter;

import java.time.Clock;

/**
 * {@code gateway.audit.enabled=true}: one JSON event per request on the
 * {@code AUDIT} logger. Decided at startup rather than with
 * {@code @ConditionalOnProperty}, which the native image would freeze at
 * build time. An {@link AuditEventSink} bean, when present, replaces the
 * default log sink.
 */
@Configuration(proxyBeanMethods = false)
public class AuditConfiguration {

    @Bean
    WebFilter accessAuditWebFilter(@Value("${gateway.audit.enabled:false}") boolean enabled,
                                   @Value("${gateway.audit.include-query:false}") boolean includeQuery,
                                   @Value("${spring.application.name:dynamic-gateway}") String serviceName,
                                   @Value("${HOSTNAME:#{null}}") String hostName,
                                   ObjectProvider<AuditEventSink> sinks) {
        if (!enabled) {
            return (exchange, chain) -> chain.filter(exchange);
        }
        AuditEventSink sink = sinks.getIfAvailable(() -> new LogAuditEventSink(serviceName, hostName));
        return new AccessAuditWebFilter(sink, includeQuery, Clock.systemUTC());
    }
}
