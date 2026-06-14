# =============================================================================
# Dockerfile para o gateway nativo.
#
# PRÉ-REQUISITO: rodar `./gradlew :gateway:nativeCompile -x test` antes
# (precisa de GraalVM 25 + zlib1g-dev + build-essential no host).
#
# O binário esperado vive em:
#   gateway/build/native/nativeCompile/dynamic-gateway
#
# A imagem resultante é apenas o binário + libc + ca-certificates,
# sem JVM nem JRE. Tamanho típico: ~165 MiB.
# =============================================================================
FROM debian:stable-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app
COPY gateway/build/native/nativeCompile/dynamic-gateway /app/dynamic-gateway

USER app
EXPOSE 8080
ENTRYPOINT ["/app/dynamic-gateway"]
