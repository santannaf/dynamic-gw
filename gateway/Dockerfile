FROM ghcr.io/graalvm/native-image-community:25 as builder
WORKDIR /workspace
COPY . .
RUN ./gradlew :gateway:nativeCompile -x test --no-daemon

FROM debian:stable-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app

COPY --from=builder /workspace/gateway/build/native/nativeCompile/dynamic-gateway /app/dynamic-gateway
#COPY gateway/build/native/nativeCompile/dynamic-gateway /app/dynamic-gateway
RUN chmod +x /app/dynamic-gateway

USER app

EXPOSE 8080

ENTRYPOINT ["/app/dynamic-gateway"]