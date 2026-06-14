# =============================================================================
# Dockerfile para o operator nativo.
#
# PRÉ-REQUISITO: rodar `./gradlew :operator:nativeCompile -x test` antes
# (precisa de GraalVM 25 + zlib1g-dev + build-essential no host).
#
# O binário esperado vive em:
#   operator/build/native/nativeCompile/dynamic-gateway-operator
#
# A imagem resultante é apenas o binário + libc + ca-certificates,
# sem JVM nem JRE. Tamanho típico: ~100 MiB.
# =============================================================================
FROM debian:stable-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app
COPY operator/build/native/nativeCompile/dynamic-gateway-operator /app/dynamic-gateway-operator

USER app
ENTRYPOINT ["/app/dynamic-gateway-operator"]
