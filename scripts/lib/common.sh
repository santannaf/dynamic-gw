# Variáveis e funções comuns aos scripts do lab. Cada script faz
#   LOG_PREFIX=<componente>
#   source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"
# e passa a rodar a partir da raiz do repositório.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT}"

NAMESPACE="${NAMESPACE:-platform}"
CLUSTER_NAME="${CLUSTER_NAME:-dynamic-gateway-lab}"
KUBE_CONTEXT="${KUBE_CONTEXT:-}"
# Porta do k3d (scripts/cluster/create-cluster.sh): o issuer do Keycloak e o redirect do login dependem dela.
HOST_PORT="${HOST_PORT:-8080}"
S3_REGION="${S3_REGION:-us-east-1}"
LOG_PREFIX="${LOG_PREFIX:-lab}"

log() {
    echo "[${LOG_PREFIX}] $*"
}

if [[ -n "${KUBE_CONTEXT}" ]]; then
    kubectl() {
        command kubectl --context "${KUBE_CONTEXT}" "$@"
    }
fi

# Fora do k3d do lab, o cluster puxa as imagens do registry, e a HOST_PORT não chega a ele.
lab_cluster() {
    [[ -z "${KUBE_CONTEXT}" || "${KUBE_CONTEXT}" == "k3d-${CLUSTER_NAME}" ]]
}

# Os manifests fixam o namespace platform: o render os leva para o NAMESPACE.
render() {
    sed -e "s|^\( *namespace:\) platform$|\1 ${NAMESPACE}|" \
        -e "s|__HOST_PORT__|${HOST_PORT}|g" \
        -e "s|__REALM_CHECKSUM__|${REALM_CHECKSUM:-none}|g" \
        -e "s|__SEED_CHECKSUM__|${SEED_CHECKSUM:-none}|g" \
        -e "s|__THEME_CHECKSUM__|${THEME_CHECKSUM:-none}|g" \
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
    lab_cluster || return 0
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

# App do gateway no Entra para o OBO (access.obo), no ConfigMap e no Secret gateway-obo, que os
# manifests do gateway leem como opcionais. Com MICROSOFT=entra é o próprio App Registration do
# login com Microsoft: o token que o Keycloak guarda desse login sai com a aud desse app, e só ele
# pode trocá-lo no OBO. Fora do entra não há OBO, e a rota com obo não é servida. O segredo vai
# por arquivo, nunca na linha de comando. Retorna 0 quando o conteúdo mudou (o gateway reinicia).
publish_gateway_obo() {
    local before after secret_file
    before="$(kubectl -n "${NAMESPACE}" get configmap gateway-obo -o 'jsonpath={.metadata.annotations.checksum}' \
        2>/dev/null || true)"
    if [[ "${MICROSOFT:-sim}" != "entra" ]]; then
        kubectl -n "${NAMESPACE}" delete configmap gateway-obo --ignore-not-found >/dev/null
        kubectl -n "${NAMESPACE}" delete secret gateway-obo --ignore-not-found >/dev/null
        [[ -n "${before}" ]]
        return
    fi
    after="$(printf '%s|%s|%s' "${MICROSOFT_TENANT_ID:?}" "${MICROSOFT_CLIENT_ID:?}" "${MICROSOFT_CLIENT_SECRET:?}" \
        | sha256sum | cut -c1-16)"
    secret_file="$(mktemp)"
    printf '%s' "${MICROSOFT_CLIENT_SECRET}" > "${secret_file}"
    kubectl -n "${NAMESPACE}" create secret generic gateway-obo --from-file=client-secret="${secret_file}" \
        --dry-run=client -o yaml | kubectl apply -f - >/dev/null
    rm -f "${secret_file}"
    kubectl -n "${NAMESPACE}" create configmap gateway-obo \
        --from-literal=tenant-id="${MICROSOFT_TENANT_ID}" --from-literal=client-id="${MICROSOFT_CLIENT_ID}" \
        --dry-run=client -o yaml \
        | kubectl annotate --local -f - checksum="${after}" -o yaml | kubectl apply -f - >/dev/null
    log "OBO no Entra: app ${MICROSOFT_CLIENT_ID} (ConfigMap e Secret gateway-obo)"
    [[ "${before}" != "${after}" ]]
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
