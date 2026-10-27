#!/usr/bin/env bash
# Sobe o SSO do gateway no k3d por cima da plataforma v2 (make deploy).
#
#   PROVIDER=keycloak ./scripts/deploy-sso.sh     # Keycloak como ponte/IdP (default)
#   PROVIDER=ldap     ./scripts/deploy-sso.sh     # gateway direto no diretório (OpenLDAP no lugar do AD)
#   ./scripts/deploy-sso.sh down                  # volta o gateway sem SSO e remove Keycloak/OpenLDAP
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
# O SSO em si vem dos profiles embarcados na imagem (application-keycloak.yaml,
# application-ldap.yaml); os manifests de k8s/sso/ só ativam os profiles e
# passam URL pública e segredos. HOST_PORT tem que ser a porta do k3d
# (create-cluster.sh): o issuer do Keycloak e o redirect do login dependem
# dela. Segredos são gerados uma vez no Secret `sso-secrets` e nunca vão para o git.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

PROVIDER="${PROVIDER:-keycloak}"
MODE="${MODE:-v2}"
HOST_PORT="${HOST_PORT:-8080}"
NAMESPACE="${NAMESPACE:-platform}"
CLUSTER_NAME="${CLUSTER_NAME:-dynamic-gateway-lab}"
S3_BACKEND="${S3_BACKEND:-minio}"
S3_REGION="${S3_REGION:-us-east-1}"
KEYCLOAK_IMAGE="quay.io/keycloak/keycloak:26.1"
OPENLDAP_IMAGE="osixia/openldap:1.5.0"
MINIO_IMAGE="bitnamilegacy/minio:2025.5.24"

render() {
    sed -e "s|__HOST_PORT__|${HOST_PORT}|g" \
        -e "s|__REALM_CHECKSUM__|${REALM_CHECKSUM:-none}|g" \
        -e "s|__SEED_CHECKSUM__|${SEED_CHECKSUM:-none}|g" \
        -e "s|__S3_BUCKET__|${S3_BUCKET:-}|g" \
        -e "s|__S3_REGION__|${S3_REGION}|g" \
        -e "s|__S3_ENDPOINT__|${S3_ENDPOINT:-}|g" \
        -e "s|__S3_PATH_STYLE__|${S3_PATH_STYLE:-false}|g" \
        -e "s|__S3_CREDENTIALS_SECRET__|${S3_CREDENTIALS_SECRET:-aws-credentials}|g" "$1"
}

secret_value() {
    kubectl -n "${NAMESPACE}" get secret sso-secrets -o "jsonpath={.data.$1}" | base64 -d
}

ensure_image() {
    local image="$1"
    if ! docker image inspect "${image}" >/dev/null 2>&1; then
        echo "[sso] Pulling ${image}"
        docker pull "${image}"
    fi
    echo "[sso] Importing ${image} into k3d cluster '${CLUSTER_NAME}'"
    k3d image import "${image}" -c "${CLUSTER_NAME}" >/dev/null
}

ensure_secrets() {
    if kubectl -n "${NAMESPACE}" get secret sso-secrets >/dev/null 2>&1; then
        echo "[sso] Secret sso-secrets already exists; keeping it"
        return
    fi
    echo "[sso] Generating Secret sso-secrets"
    kubectl -n "${NAMESPACE}" create secret generic sso-secrets \
        --from-literal=tools-gateway-client-secret="$(openssl rand -hex 24)" \
        --from-literal=tools-cli-client-secret="$(openssl rand -hex 24)" \
        --from-literal=demo-password="$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-16)" \
        --from-literal=session-key="$(openssl rand -base64 32)" \
        --from-literal=keycloak-admin-password="$(openssl rand -hex 16)" \
        --from-literal=ldap-admin-password="$(openssl rand -hex 16)" \
        --from-literal=ldap-readonly-password="$(openssl rand -hex 16)"
}

wait_rollout() {
    kubectl -n "${NAMESPACE}" rollout status "deployment/$1" --timeout="${2:-300s}"
}

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
    echo "[sso] WARNING: the edge still answers ${code} through nginx-lb"
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
                echo "[sso] Generating Secret minio-credentials"
                kubectl -n "${NAMESPACE}" create secret generic minio-credentials \
                    --from-literal=AWS_ACCESS_KEY_ID="lab-$(openssl rand -hex 6)" \
                    --from-literal=AWS_SECRET_ACCESS_KEY="$(openssl rand -hex 24)"
            fi
            ensure_image "${MINIO_IMAGE}"
            render k8s/s3/minio.yaml | kubectl apply -f -
            wait_rollout minio 180s
            ;;
        aws)
            if [[ -z "${S3_BUCKET:-}" ]]; then
                echo "S3_BACKEND=aws needs S3_BUCKET (create it with scripts/create-s3-bucket.sh)" >&2
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
    echo "[sso] Route snapshots in s3://${S3_BUCKET}/snapshots/routes.yaml (backend=${S3_BACKEND})"
}

down() {
    echo "[sso] Restoring the gateway without SSO (MODE=${MODE})"
    if [[ "${MODE}" == "standalone" ]]; then
        kubectl apply -f k8s/gateway-standalone/deployment.yaml
    else
        kubectl apply -f k8s/gateway/deployment.yaml
        kubectl -n "${NAMESPACE}" delete -f k8s/samples/routes-sso.yaml --ignore-not-found
        # O cenário S3 troca o operator; v2 e s3 voltam para a ConfigMap.
        if operator_exists; then
            kubectl apply -f k8s/operator/deployment.yaml
        fi
        render k8s/s3/minio.yaml | kubectl delete --ignore-not-found -f -
        kubectl -n "${NAMESPACE}" delete secret minio-credentials --ignore-not-found
    fi
    render k8s/sso/keycloak.yaml | kubectl delete --ignore-not-found -f -
    render k8s/sso/openldap.yaml | kubectl delete --ignore-not-found -f -
    kubectl -n "${NAMESPACE}" delete configmap keycloak-realm-tools --ignore-not-found
    kubectl -n "${NAMESPACE}" delete secret openldap-seed --ignore-not-found
    wait_rollout dynamic-gateway 180s
    echo "[sso] Down. Secret sso-secrets kept (delete it by hand to rotate everything)."
}

if [[ "${MODE}" != "v2" && "${MODE}" != "s3" && "${MODE}" != "standalone" ]]; then
    echo "MODE must be v2, s3 or standalone (got '${MODE}')" >&2
    exit 2
fi

if [[ "${1:-}" == "down" ]]; then
    down
    exit 0
fi

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
            echo "[sso] WARNING: the v2 operator is running in this namespace. Its ConfigMap writes and reload signals"
            echo "      are no-ops for the properties store, but v1 and v2 should not share a cluster (see CLAUDE.md)."
        fi
        ;;
esac
ensure_secrets

case "${PROVIDER}" in
    keycloak)
        ensure_image "${KEYCLOAK_IMAGE}"
        kubectl -n "${NAMESPACE}" create configmap keycloak-realm-tools \
            --from-file=realm-tools.json=k8s/sso/realm-tools.json \
            --dry-run=client -o yaml | kubectl apply -f -
        # O checksum no pod template recria o Keycloak só quando o realm muda
        # (modo dev: reiniciar apaga sessões e reimporta usuários com IDs novos).
        REALM_CHECKSUM="$(sed "s/__HOST_PORT__/${HOST_PORT}/g" k8s/sso/realm-tools.json | sha256sum | cut -c1-16)"
        render k8s/sso/keycloak.yaml | kubectl apply -f -
        wait_rollout keycloak 600s
        ;;
    ldap)
        ensure_image "${OPENLDAP_IMAGE}"
        demo_password="$(secret_value demo-password)"
        sed "s/__DEMO_PASSWORD__/${demo_password}/g" k8s/sso/openldap-seed.ldif \
            | kubectl -n "${NAMESPACE}" create secret generic openldap-seed \
                --from-file=50-tools.ldif=/dev/stdin --dry-run=client -o yaml \
            | kubectl apply -f -
        SEED_CHECKSUM="$(sed "s/__DEMO_PASSWORD__/${demo_password}/g" k8s/sso/openldap-seed.ldif | sha256sum | cut -c1-16)"
        render k8s/sso/openldap.yaml | kubectl apply -f -
        wait_rollout openldap 180s
        ;;
    *)
        echo "PROVIDER must be keycloak or ldap (got '${PROVIDER}')" >&2
        exit 2
        ;;
esac

if [[ "${MODE}" == "s3" ]]; then
    setup_s3_backend
    # Operator primeiro: ele publica o snapshot no bucket antes de o gateway ler.
    render k8s/sso/operator-s3.yaml | kubectl apply -f -
    wait_rollout gateway-route-operator 180s
    # O operator não tem readiness: sem esperar o primeiro publish, o gateway
    # leria "objeto não existe" e ficaria com 0 rotas até o próximo poll.
    published=0
    for _ in $(seq 1 60); do
        published="$(kubectl -n "${NAMESPACE}" logs deployment/gateway-route-operator | grep -c 'Published snapshot to s3://' || true)"
        [[ "${published}" -gt 0 ]] && break
        sleep 2
    done
    if [[ "${published}" -eq 0 ]]; then
        echo "[sso] WARNING: no snapshot published to S3 yet; check: kubectl -n ${NAMESPACE} logs deploy/gateway-route-operator"
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
fi
kubectl apply -f k8s/nginx-lb/ >/dev/null
kubectl -n "${NAMESPACE}" rollout restart deployment/nginx-lb >/dev/null
wait_rollout nginx-lb 120s
wait_rollout dynamic-gateway 300s
wait_edge

echo
echo "[sso] Up with provider=${PROVIDER}, mode=${MODE} at http://localhost:${HOST_PORT}"
echo "      Users (local only): alice (tools-httpbin), bob (no groups), carol (tools-admins)"
echo "      Password:           $(secret_value demo-password)"
echo "      Try:                http://localhost:${HOST_PORT}${TRY_PATH}"
if [[ "${PROVIDER}" == "keycloak" ]]; then
    echo "      Keycloak admin:     http://localhost:${HOST_PORT}/auth/admin (admin / $(secret_value keycloak-admin-password))"
fi
