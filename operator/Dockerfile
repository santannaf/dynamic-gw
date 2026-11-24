# Imagem do operator, de três jeitos:
#   padrão                JVM, com o jar de ./gradlew :operator:bootJar (make images, make deploy)
#   --target host         nativa, com o binário de ./gradlew :operator:nativeCompile compilado no host Linux
#                         (make lab linux)
#   --target container    nativa, compilada aqui dentro, num container Linux, para a arquitetura do Docker
#                         (make lab mac)
FROM ghcr.io/graalvm/native-image-community:25 AS builder
WORKDIR /workspace
COPY . .
RUN ./gradlew :operator:nativeCompile -x test --no-daemon

FROM debian:stable-slim AS native

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app

ENTRYPOINT ["/app/dynamic-gateway-operator"]

FROM native AS host
COPY --chmod=755 operator/build/native/nativeCompile/dynamic-gateway-operator /app/dynamic-gateway-operator
USER app

FROM native AS container
COPY --chmod=755 --from=builder /workspace/operator/build/native/nativeCompile/dynamic-gateway-operator /app/dynamic-gateway-operator
USER app

FROM eclipse-temurin:25-jre AS runtime

WORKDIR /app

RUN useradd --no-create-home --shell /usr/sbin/nologin app

COPY operator/build/libs/operator-0.0.1.jar /app/dynamic-gateway-operator.jar

USER app

ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/dynamic-gateway-operator.jar"]
