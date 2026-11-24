# Imagem nativa do gateway, de dois jeitos:
#   padrão           compila aqui dentro, num container Linux, para a arquitetura do Docker
#                    (make images, make lab mac)
#   --target host    empacota o binário de ./gradlew :gateway:nativeCompile, compilado no host Linux
#                    (make lab linux)
FROM ghcr.io/graalvm/native-image-community:25 AS builder
WORKDIR /workspace
COPY . .
RUN ./gradlew :gateway:nativeCompile -x test --no-daemon

FROM debian:stable-slim AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app

EXPOSE 8080

ENTRYPOINT ["/app/dynamic-gateway"]

FROM runtime AS host
COPY --chmod=755 gateway/build/native/nativeCompile/dynamic-gateway /app/dynamic-gateway
USER app

FROM runtime AS container
COPY --chmod=755 --from=builder /workspace/gateway/build/native/nativeCompile/dynamic-gateway /app/dynamic-gateway
USER app
