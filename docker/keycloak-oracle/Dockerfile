# Keycloak com o driver JDBC da Oracle, que não vem na distribuição (licença).
# Montado por scripts/keycloak/keycloak.sh quando KEYCLOAK_DB=oracle.
FROM quay.io/keycloak/keycloak:26.1

ARG OJDBC_VERSION=23.6.0.24.10
ADD --chown=1000:0 https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc11/${OJDBC_VERSION}/ojdbc11-${OJDBC_VERSION}.jar /opt/keycloak/providers/ojdbc11.jar
ADD --chown=1000:0 https://repo1.maven.org/maven2/com/oracle/database/nls/orai18n/${OJDBC_VERSION}/orai18n-${OJDBC_VERSION}.jar /opt/keycloak/providers/orai18n.jar

# As mesmas opções de build do Deployment: o `start` sobe sem refazer o build.
RUN /opt/keycloak/bin/kc.sh build --db=oracle --health-enabled=true --http-relative-path=/auth
