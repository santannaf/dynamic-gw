# Variáveis e funções comuns aos scripts do lab. Cada script faz
#   LOG_PREFIX=<componente>
#   source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"
# e passa a rodar a partir da raiz do repositório.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT}"

NAMESPACE="${NAMESPACE:-platform}"
CLUSTER_NAME="${CLUSTER_NAME:-dynamic-gateway-lab}"
# Porta do k3d (scripts/cluster/create-cluster.sh): o issuer do Keycloak e o redirect do login dependem dela.
HOST_PORT="${HOST_PORT:-8080}"
S3_REGION="${S3_REGION:-us-east-1}"
LOG_PREFIX="${LOG_PREFIX:-lab}"

log() {
    echo "[${LOG_PREFIX}] $*"
}

render() {
    sed -e "s|__HOST_PORT__|${HOST_PORT}|g" \
        -e "s|__REALM_CHECKSUM__|${REALM_CHECKSUM:-none}|g" \
        -e "s|__SEED_CHECKSUM__|${SEED_CHECKSUM:-none}|g" \
        -e "s|__S3_BUCKET__|${S3_BUCKET:-}|g" \
        -e "s|__S3_REGION__|${S3_REGION}|g" \
        -e "s|__S3_ENDPOINT__|${S3_ENDPOINT:-}|g" \
        -e "s|__S3_PATH_STYLE__|${S3_PATH_STYLE:-false}|g" \
        -e "s|__S3_CREDENTIALS_SECRET__|${S3_CREDENTIALS_SECRET:-aws-credentials}|g" \
        -e "s|__KEYCLOAK_IMAGE__|${KEYCLOAK_IMAGE:-}|g" \
        -e "s|__ORACLE_IMAGE__|${ORACLE_IMAGE:-}|g" "$1"
}

# Espera um rollout: wait_rollout deployment/keycloak 600s
wait_rollout() {
    kubectl -n "${NAMESPACE}" rollout status "$1" --timeout="${2:-300s}"
}

ensure_image() {
    local image="$1"
    if ! docker image inspect "${image}" >/dev/null 2>&1; then
        log "Pulling ${image}"
        docker pull "${image}"
    fi
    log "Importing ${image} into k3d cluster '${CLUSTER_NAME}'"
    k3d image import "${image}" -c "${CLUSTER_NAME}" >/dev/null
}

secret_value() {
    kubectl -n "${NAMESPACE}" get secret sso-secrets -o "jsonpath={.data.$1}" | base64 -d
}

# Segredos do SSO, gerados uma vez e nunca no git: apague o Secret para rotacionar tudo.
ensure_sso_secrets() {
    if kubectl -n "${NAMESPACE}" get secret sso-secrets >/dev/null 2>&1; then
        return
    fi
    log "Generating Secret sso-secrets"
    kubectl -n "${NAMESPACE}" create secret generic sso-secrets \
        --from-literal=tools-gateway-client-secret="$(openssl rand -hex 24)" \
        --from-literal=tools-cli-client-secret="$(openssl rand -hex 24)" \
        --from-literal=demo-password="$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-16)" \
        --from-literal=session-key="$(openssl rand -base64 32)" \
        --from-literal=keycloak-admin-password="$(openssl rand -hex 16)" \
        --from-literal=ldap-admin-password="$(openssl rand -hex 16)" \
        --from-literal=ldap-readonly-password="$(openssl rand -hex 16)" >/dev/null
}

# Conexão que o Keycloak usa (envFrom em k8s/sso/keycloak.yaml): o ConfigMap
# keycloak-db leva KC_DB e KC_DB_URL, e o Secret keycloak-db, usuário e senha
# copiados do Secret do banco. Cada script de scripts/db/ publica a sua no `up`.
publish_keycloak_db() {
    local vendor="$1" url="$2" secret="$3" username password
    kubectl -n "${NAMESPACE}" create configmap keycloak-db \
        --from-literal=KC_DB="${vendor}" --from-literal=KC_DB_URL="${url}" \
        --dry-run=client -o yaml | kubectl apply -f - >/dev/null
    username="$(kubectl -n "${NAMESPACE}" get secret "${secret}" -o jsonpath='{.data.username}')"
    password="$(kubectl -n "${NAMESPACE}" get secret "${secret}" -o jsonpath='{.data.password}')"
    # Valores já em base64 e pelo stdin: a senha não passa por argumento de processo.
    kubectl apply -f - >/dev/null <<SECRET
apiVersion: v1
kind: Secret
metadata:
  name: keycloak-db
  namespace: ${NAMESPACE}
type: Opaque
data:
  KC_DB_USERNAME: ${username}
  KC_DB_PASSWORD: ${password}
SECRET
}
