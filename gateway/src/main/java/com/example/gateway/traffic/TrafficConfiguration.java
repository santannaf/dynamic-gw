package com.example.gateway.traffic;

import com.example.shared.routes.RoutePolicyRules;
import com.example.shared.routes.RouteRateLimit;
import com.example.shared.routes.RouteThrottle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * The route policy filters. They are always registered and do nothing for a
 * route without the policy: deciding by condition would freeze the choice
 * when the native image is built. An invalid global ceiling stops the startup.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TrafficProperties.class)
public class TrafficConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TrafficConfiguration.class);

    @Bean
    public RateLimitFilter rateLimitFilter(TrafficProperties traffic) {
        RouteRateLimit global = traffic.getRateLimit().toPolicy();
        if (global != null) {
            RoutePolicyRules.rateLimit(global).ifPresent(problem -> {
                throw new IllegalStateException("gateway.traffic: " + problem);
            });
            log.info("Global rate limit: {} requests per {} per replica (burst={}, key={})",
                    global.requests(), global.period(), global.burst(), global.key() == null ? "route" : global.key());
        }
        return new RateLimitFilter(new RateLimiters(traffic.getRateLimit().getMaxKeys(), System::nanoTime),
                global, traffic.getTrustedProxies());
    }

    @Bean
    public ThrottleFilter throttleFilter(TrafficProperties traffic) {
        RouteThrottle global = traffic.getThrottle().toPolicy();
        if (global != null) {
            RoutePolicyRules.throttle(global).ifPresent(problem -> {
                throw new IllegalStateException("gateway.traffic: " + problem);
            });
            log.info("Global throttle: {} concurrent requests per replica (queue={}, wait={})",
                    global.maxConcurrent(), global.maxQueue(), global.maxWait());
        }
        return new ThrottleFilter(global);
    }

    @Bean
    public ResponseTransformFilter responseTransformFilter(TrafficProperties traffic) {
        return new ResponseTransformFilter(traffic.getTransform().getMaxBodySize().toBytes());
    }

    @Bean
    public AggregationFilter aggregationFilter(TrafficProperties traffic, ObjectProvider<HttpClient> httpClient) {
        // The gateway's own HTTP client: same pool, timeouts and TLS settings as the proxied routes.
        WebClient client = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient.getIfAvailable(HttpClient::create)))
                .codecs(codecs -> codecs.defaultCodecs()
                        .maxInMemorySize((int) traffic.getAggregate().getMaxBodySize().toBytes()))
                .build();
        return new AggregationFilter(client, traffic.getAggregate().getTimeout());
    }
}
