package com.example.gateway.traffic;

import com.example.gateway.routing.RouteDefinitionMapper;
import com.example.shared.routes.RouteTransform;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Applies the route's {@code transform} to successful JSON responses, its own
 * upstream's or the one built by {@link AggregationFilter}. The body is
 * buffered up to {@code gateway.traffic.transform.max-body-size}; a larger
 * one, and anything that is not JSON, goes out untouched.
 */
public class ResponseTransformFilter implements GlobalFilter, Ordered {

    public static final int ORDER = NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER - 1;

    private final long maxBodyBytes;
    private final JsonMapper json = JsonMapper.builder().build();

    public ResponseTransformFilter(long maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        RouteTransform transform = route != null
                && route.getMetadata().get(RouteDefinitionMapper.TRANSFORM_METADATA_KEY) instanceof RouteTransform policy
                ? policy : null;
        if (transform == null) {
            return chain.filter(exchange);
        }
        // A compressed body cannot be read here: ask the upstream for plain JSON.
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.remove(HttpHeaders.ACCEPT_ENCODING))
                .build();
        ServerHttpResponse response = new TransformingResponse(exchange.getResponse(), transform,
                exchange.getRequest().getMethod());
        return chain.filter(exchange.mutate().request(request).response(response).build());
    }

    private final class TransformingResponse extends ServerHttpResponseDecorator {

        private final RouteTransform transform;
        private final HttpMethod method;

        TransformingResponse(ServerHttpResponse delegate, RouteTransform transform, HttpMethod method) {
            super(delegate);
            this.transform = transform;
            this.method = method;
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            if (!transformable() || getHeaders().getContentLength() > maxBodyBytes) {
                return super.writeWith(body);
            }
            AtomicLong seen = new AtomicLong();
            return Flux.from(body)
                    .bufferUntil(buffer -> seen.addAndGet(buffer.readableByteCount()) > maxBodyBytes)
                    .switchOnFirst((first, parts) -> {
                        // One list within the limit means the whole body: bufferUntil
                        // only emits early once the limit is crossed.
                        if (first.hasValue() && seen.get() <= maxBodyBytes) {
                            return super.writeWith(Mono.fromSupplier(() -> transformed(first.get())));
                        }
                        return super.writeWith(parts.concatMapIterable(Function.identity()));
                    })
                    .then();
        }

        private boolean transformable() {
            HttpStatusCode status = getStatusCode();
            MediaType type = getHeaders().getContentType();
            String encoding = getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING);
            return !HttpMethod.HEAD.equals(method)
                    && status != null && status.is2xxSuccessful()
                    && (encoding == null || "identity".equalsIgnoreCase(encoding))
                    && type != null
                    && (type.isCompatibleWith(MediaType.APPLICATION_JSON) || type.getSubtype().endsWith("+json"));
        }

        private DataBuffer transformed(List<? extends DataBuffer> parts) {
            byte[] original = join(parts);
            byte[] body;
            try {
                body = json.writeValueAsBytes(JsonTransformer.apply(json.readTree(original), transform));
            } catch (JacksonException notJson) {
                body = original;
            }
            getHeaders().setContentLength(body.length);
            getHeaders().remove(HttpHeaders.ETAG);
            return bufferFactory().wrap(body);
        }
    }

    private static byte[] join(List<? extends DataBuffer> parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (DataBuffer part : parts) {
            byte[] chunk = new byte[part.readableByteCount()];
            part.read(chunk);
            out.writeBytes(chunk);
            DataBufferUtils.release(part);
        }
        return out.toByteArray();
    }
}
