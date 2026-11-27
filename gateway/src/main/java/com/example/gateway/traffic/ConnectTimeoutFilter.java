package com.example.gateway.traffic;

import io.netty.channel.ConnectTimeoutException;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.NettyRoutingFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Answers 504 when the connection to the backend runs out of time
 * ({@code timeouts.connect} or the global connect-timeout), as Spring Cloud
 * Gateway already does when the response runs out of time. Without it the
 * caller would get a 500, as if the gateway had failed.
 */
public class ConnectTimeoutFilter implements GlobalFilter, Ordered {

    // Right before WebsocketRoutingFilter (NettyRoutingFilter.ORDER - 1) and
    // NettyRoutingFilter, so no other filter sees the connect error first.
    public static final int ORDER = NettyRoutingFilter.ORDER - 2;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).onErrorMap(ConnectTimeoutException.class,
                timeout -> new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                        "Connection to the backend took longer than its timeout", timeout));
    }
}
