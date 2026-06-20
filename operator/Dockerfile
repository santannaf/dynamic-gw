FROM eclipse-temurin:25-jre as runtime

WORKDIR /app

RUN useradd --no-create-home --shell /usr/sbin/nologin app

COPY operator/build/libs/operator-0.0.1.jar /app/dynamic-gateway-operator.jar

USER app

ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/dynamic-gateway-operator.jar"]
