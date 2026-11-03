#!/usr/bin/env bash
# Sobe o SSO do gateway no k3d por cima da plataforma v2 (make deploy). Orquestra
# as peças de scripts/keycloak/, scripts/db/ e scripts/ldap/ e troca o gateway
# pelo manifest do provider.
#
#   PROVIDER=keycloak scripts/sso/deploy-sso.sh     # Keycloak como ponte/IdP (default)
#   PROVIDER=ldap     scripts/sso/deploy-sso.sh     # gateway direto no diretório (OpenLDAP no lugar do AD)
#   PROVIDER=oauth    scripts/sso/deploy-sso.sh     # API pública: só tokens, sem sessão (Keycloak como emissor)
#   scripts/sso/deploy-sso.sh down                  # volta o gateway sem SSO e remove Keycloak, banco e OpenLDAP
#
# MODE escolhe o cenário de rotas (um .md na raiz para cada):
#   v2 (default)  CRD -> operator -> ConfigMap, rotas de k8s/samples/routes-sso.yaml   (SSO_CENARIO_CRD.md)
#   s3            CRD -> operator -> objeto S3, mesmas rotas                           (SSO_CENARIO_S3.md)
#   standalone    v1: rotas com `access` embarcadas na imagem (profile standalone-sso) (SSO_CENARIO_PROPERTIES.md)
#
# MODE=s3 usa S3_BACKEND=minio (default: MinIO no cluster, sem conta AWS) ou
# S3_BACKEND=aws (bucket real: S3_BUCKET obrigatório e o Secret aws-credentials
# criado a partir de k8s/s3/aws-credentials.example.yaml). S3_REGION default us-east-1.
#
# KEYCLOAK_DB (postgres ou oracle), DIRECTORY (openldap ou ad) e as variáveis do
# AD estão no cabeçalho de scripts/keycloak/keycloak.sh.
#
# O SSO em si vem dos profiles embarcados na imagem (application-keycloak.yaml,
# application-ldap.yaml); os manifests de k8s/sso/ só ativam os profiles e
# passam URL pública e segredos. HOST_PORT tem que ser a porta do k3d.
set -euo pipefail
LOG_PREFIX=sso
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"

PROVIDER="${PROVIDER:-keycloak}"
MODE="${MODE:-v2}"
DIRECTORY="${DIRECTORY:-openldap}"
S3_BACKEND="${S3_BACKEND:-minio}"
MINIO_IMAGE="bitnamilegacy/minio:2025.5.24"
KEYCLOAK="${ROOT}/scripts/keycloak/keycloak.sh"
OPENLDAP="${ROOT}/scripts/ldap/openldap.sh"

# Logo depois de uma troca, o cache de DNS (CoreDNS e o resolver do nginx-lb) ainda
# aponta para pods que saíram e a borda devolve 502: espera 15 respostas seguidas
# vindas de um gateway vivo (qualquer status abaixo de 500).
wait_edge() {
    local ok=0 code=""
    for _ in $(seq 1 120); do
        code="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:${HOST_PORT}/_gateway/whoami" || true)"
        if [[ "${code}" =~ ^[1-4][0-9][0-9]$ ]]; then ok=$((ok + 1)); else ok=0; fi
        [[ "${ok}" -ge 15 ]] && return 0
        sleep 1
    done
    log "WARNING: the edge still answers ${code} through nginx-lb"
}

operator_exists() {
    kubectl -n "${NAMESPACE}" get deployment gateway-route-operator >/dev/null 2>&1
}

# Bucket, endpoint e credenciais do cenário S3: MinIO no cluster ou AWS de verdade.
setup_s3_backend() {
    case "${S3_BACKEND}" in
        minio)
            S3_BUCKET="${S3_BUCKET:-dynamic-gateway-routes}"
            S3_ENDPOINT="http://minio.${NAMESPACE}.svc.cluster.local:9000"
            S3_PATH_STYLE=true
            S3_CREDENTIALS_SECRET=minio-credentials
            if ! kubectl -n "${NAMESPACE}" get secret minio-credentials >/dev/null 2>&1; then
                log "Generating Secret minio-credentials"
                kubectl -n "${NAMESPACE}" create secret generic minio-credentials \
                    --from-literal=AWS_ACCESS_KEY_ID="lab-$(openssl rand -hex 6)" \
                    --from-literal=AWS_SECRET_ACCESS_KEY="$(openssl rand -hex 24)"
            fi
            ensure_image "${MINIO_IMAGE}"
            render k8s/s3/minio.yaml | kubectl apply -f -
            wait_rollout deployment/minio 180s
            ;;
        aws)
            if [[ -z "${S3_BUCKET:-}" ]]; then
                echo "S3_BACKEND=aws needs S3_BUCKET (create it with scripts/s3/create-s3-bucket.sh)" >&2
                exit 2
            fi
            if ! kubectl -n "${NAMESPACE}" get secret aws-credentials >/dev/null 2>&1; then
                echo "S3_BACKEND=aws needs the Secret aws-credentials (see k8s/s3/aws-credentials.example.yaml)" >&2
                exit 2
            fi
            S3_ENDPOINT=""
            S3_PATH_STYLE=false
            S3_CREDENTIALS_SECRET=aws-credentials
            ;;
        *)
            echo "S3_BACKEND must be minio or aws (got '${S3_BACKEND}')" >&2
            exit 2
            ;;
    esac
    log "Route snapshots in s3://${S3_BUCKET}/snapshots/routes.yaml (backend=${S3_BACKEND})"
}

down() {
    log "Restoring the gateway without SSO (MODE=${MODE})"
    if [[ "${MODE}" == "standalone" ]]; then
        kubectl apply -f k8s/gateway-standalone/deployment.yaml
    else
        kubectl apply -f k8s/gateway/deployment.yaml
        kubectl -n "${NAMESPACE}" delete -f k8s/samples/routes-sso.yaml --ignore-not-found
        kubectl -n "${NAMESPACE}" delete -f k8s/samples/routes-oauth.yaml --ignore-not-found
        # O cenário S3 troca o operator; v2 e s3 voltam para a ConfigMap.
        if operator_exists; then
            kubectl apply -f k8s/operator/deployment.yaml
        fi
        render k8s/s3/minio.yaml | kubectl delete --ignore-not-found -f -
        kubectl -n "${NAMESPACE}" delete secret minio-credentials --ignore-not-found
    fi
    "${KEYCLOAK}" down
    "${ROOT}/scripts/db/postgres.sh" down
    "${ROOT}/scripts/db/oracle.sh" down
    "${OPENLDAP}" down
    wait_rollout deployment/dynamic-gateway 180s
    log "Down. Secrets sso-secrets, openldap-tls, keycloak-postgres and keycloak-oracle kept (delete them by hand to rotate everything)."
}

if [[ "${MODE}" != "v2" && "${MODE}" != "s3" && "${MODE}" != "standalone" ]]; then
    echo "MODE must be v2, s3 or standalone (got '${MODE}')" >&2
    exit 2
fi

if [[ "${1:-}" == "down" ]]; then
    down
    exit 0
fi

case "${PROVIDER}" in
    keycloak|oauth)
        "${KEYCLOAK}" check
        ;;
    ldap)
        if [[ "${DIRECTORY}" != "openldap" ]]; then
            echo "DIRECTORY only applies to the Keycloak federation; with PROVIDER=ldap the gateway reads" >&2
            echo "GATEWAY_SECURITY_LDAP_* (see SSO_CENARIO_CRD.md, 'Em outro ambiente')" >&2
            exit 2
        fi
        ;;
    *)
        echo "PROVIDER must be keycloak, ldap or oauth (got '${PROVIDER}')" >&2
        exit 2
        ;;
esac

kubectl apply -f k8s/namespace.yaml >/dev/null
case "${MODE}" in
    v2)
        kubectl apply -f k8s/crd/gatewayroutes.platform.saca.pags.yaml >/dev/null
        GATEWAY_MANIFEST="k8s/sso/gateway-${PROVIDER}.yaml"
        # Volta o operator para a ConfigMap se o cluster estava no cenário S3.
        if operator_exists; then
            kubectl apply -f k8s/operator/deployment.yaml >/dev/null
        fi
        ;;
    s3)
        if ! operator_exists; then
            echo "MODE=s3 runs on top of the v2 platform: run 'make deploy' first" >&2
            exit 2
        fi
        kubectl apply -f k8s/crd/gatewayroutes.platform.saca.pags.yaml >/dev/null
        GATEWAY_MANIFEST="k8s/sso/gateway-s3-${PROVIDER}.yaml"
        ;;
    standalone)
        GATEWAY_MANIFEST="k8s/sso/gateway-standalone-${PROVIDER}.yaml"
        if operator_exists; then
            log "WARNING: the v2 operator is running in this namespace. Its ConfigMap writes and reload signals"
            log "         are no-ops for the properties store, but v1 and v2 should not share a cluster (see CLAUDE.md)."
        fi
        ;;
esac
if [[ ! -f "${GATEWAY_MANIFEST}" ]]; then
    echo "MODE=${MODE} has no manifest for PROVIDER=${PROVIDER} (${GATEWAY_MANIFEST})" >&2
    exit 2
fi
ensure_sso_secrets

case "${PROVIDER}" in
    keycloak|oauth) "${KEYCLOAK}" up ;;
    ldap) "${OPENLDAP}" up ;;
esac

if [[ "${MODE}" == "s3" ]]; then
    setup_s3_backend
    # Operator primeiro: ele publica o snapshot no bucket antes de o gateway ler.
    render k8s/sso/operator-s3.yaml | kubectl apply -f -
    wait_rollout deployment/gateway-route-operator 180s
    # O operator não tem readiness: sem esperar o primeiro publish, o gateway
    # leria "objeto não existe" e ficaria com 0 rotas até o próximo poll.
    published=0
    for _ in $(seq 1 60); do
        published="$(kubectl -n "${NAMESPACE}" logs deployment/gateway-route-operator | grep -c 'Published snapshot to s3://' || true)"
        [[ "${published}" -gt 0 ]] && break
        sleep 2
    done
    if [[ "${published}" -eq 0 ]]; then
        log "WARNING: no snapshot published to S3 yet; check: kubectl -n ${NAMESPACE} logs deploy/gateway-route-operator"
    fi
fi

render "${GATEWAY_MANIFEST}" | kubectl apply -f -
kubectl apply -f k8s/samples/httpbin-in-cluster.yaml >/dev/null
if [[ "${MODE}" == "standalone" ]]; then
    kubectl apply -f k8s/gateway-standalone/service.yaml -f k8s/gateway-standalone/ingress.yaml >/dev/null
    TRY_PATH=/httpbin/headers
else
    kubectl apply -f k8s/samples/routes-sso.yaml
    TRY_PATH=/sso/group/headers
    if [[ "${PROVIDER}" == "oauth" ]]; then
        kubectl apply -f k8s/samples/routes-oauth.yaml
        TRY_PATH=/oauth/orders/headers
    fi
fi
kubectl apply -f k8s/nginx-lb/ >/dev/null
kubectl -n "${NAMESPACE}" rollout restart deployment/nginx-lb >/dev/null
wait_rollout deployment/nginx-lb 120s
wait_rollout deployment/dynamic-gateway 300s
wait_edge

echo
log "Up with provider=${PROVIDER}, mode=${MODE} at http://localhost:${HOST_PORT}"
if [[ "${PROVIDER}" == "oauth" ]]; then
    echo "      Token:              client tools-cli (client_credentials, secret in sso-secrets)"
elif [[ "${DIRECTORY}" == "ad" ]]; then
    echo "      Users:              from the AD (${LDAP_USERS_DN}); permissions from the groups in ${LDAP_GROUPS_DN}"
else
    echo "      Users (directory):  alice (tools-httpbin), bob (no groups), carol (tools-admins, tools-httpbin),"
    echo "                          dave (gateway-admins: operates the gateway, no tool access)"
    echo "      Password:           $(secret_value demo-password)"
fi
echo "      Try:                http://localhost:${HOST_PORT}${TRY_PATH}"
if [[ "${PROVIDER}" != "ldap" ]]; then
    echo "      Keycloak admin:     http://localhost:${HOST_PORT}/auth/admin (admin / $(secret_value keycloak-admin-password))"
    echo "      Keycloak database:  ${KEYCLOAK_DB:-postgres} (scripts/db/${KEYCLOAK_DB:-postgres}.sh shell)"
fi
