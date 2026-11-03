#!/usr/bin/env bash
# Sobe o SSO do gateway no k3d por cima da plataforma v2 (make deploy).
#
#   PROVIDER=keycloak ./scripts/deploy-sso.sh     # Keycloak como ponte/IdP (default)
#   PROVIDER=ldap     ./scripts/deploy-sso.sh     # gateway direto no diretório (OpenLDAP no lugar do AD)
#   PROVIDER=oauth    ./scripts/deploy-sso.sh     # API pública: só tokens, sem sessão (Keycloak como emissor)
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
#
# DIRECTORY escolhe o diretório que o Keycloak federa (PROVIDER=keycloak ou oauth),
# sempre por LDAPS e somente leitura:
#   openldap (default)  OpenLDAP do lab no lugar do AD, com certificado da CA do lab
#   ad                  o AD da POC. Obrigatórios (a senha nunca vai para arquivo do repo):
#       LDAP_URL=ldaps://ad.corp.local:636
#       LDAP_BIND_DN='CN=svc-tools-gateway,OU=Services,DC=corp,DC=local'   conta só de leitura
#       LDAP_BIND_PASSWORD=...
#       LDAP_USERS_DN='OU=Users,DC=corp,DC=local'
#       LDAP_GROUPS_DN='OU=Tools,OU=Groups,DC=corp,DC=local'   OU da aplicação: só esses grupos viram permissão
#       LDAP_CA_FILE=/caminho/ca-corporativa.pem               CA que assina o certificado do AD
#     Opcionais: LDAP_USERNAME_ATTRIBUTE (sAMAccountName; userPrincipalName para logar com e-mail)
#                GATEWAY_ADMINS_GROUP (gateway-admins): o grupo da OU que recebe a role gateway-admin.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

PROVIDER="${PROVIDER:-keycloak}"
MODE="${MODE:-v2}"
DIRECTORY="${DIRECTORY:-openldap}"
GATEWAY_ADMINS_GROUP="${GATEWAY_ADMINS_GROUP:-gateway-admins}"
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

# CA do lab e certificado do OpenLDAP (LDAPS na 636). Gerados uma vez, como o
# sso-secrets; apague o Secret openldap-tls para gerar outros.
ensure_openldap_tls() {
    if kubectl -n "${NAMESPACE}" get secret openldap-tls >/dev/null 2>&1; then
        return
    fi
    echo "[sso] Generating the lab CA and the OpenLDAP certificate (Secret openldap-tls)"
    local dir host="openldap.${NAMESPACE}.svc.cluster.local"
    dir="$(mktemp -d)"
    openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=dynamic-gateway lab CA" \
        -keyout "${dir}/ca.key" -out "${dir}/ca.crt" 2>/dev/null
    openssl req -newkey rsa:2048 -nodes -subj "/CN=${host}" \
        -keyout "${dir}/tls.key" -out "${dir}/tls.csr" 2>/dev/null
    printf 'subjectAltName=DNS:%s,DNS:openldap,DNS:openldap.%s,DNS:openldap.%s.svc\nextendedKeyUsage=serverAuth\n' \
        "${host}" "${NAMESPACE}" "${NAMESPACE}" > "${dir}/ext.cnf"
    openssl x509 -req -in "${dir}/tls.csr" -CA "${dir}/ca.crt" -CAkey "${dir}/ca.key" -CAcreateserial \
        -days 825 -extfile "${dir}/ext.cnf" -out "${dir}/tls.crt" 2>/dev/null
    # Sem este arquivo a imagem gera um no boot, a cada pod novo.
    openssl dhparam -out "${dir}/dhparam.pem" 2048 2>/dev/null
    kubectl -n "${NAMESPACE}" create secret generic openldap-tls \
        --from-file=ca.crt="${dir}/ca.crt" --from-file=tls.crt="${dir}/tls.crt" \
        --from-file=tls.key="${dir}/tls.key" --from-file=dhparam.pem="${dir}/dhparam.pem" >/dev/null
    rm -rf "${dir}"
}

ensure_openldap() {
    ensure_image "${OPENLDAP_IMAGE}"
    ensure_openldap_tls
    local seed
    seed="$(sed "s/__DEMO_PASSWORD__/$(secret_value demo-password)/g" k8s/sso/openldap-seed.ldif)"
    printf '%s\n' "${seed}" \
        | kubectl -n "${NAMESPACE}" create secret generic openldap-seed \
            --from-file=50-tools.ldif=/dev/stdin --dry-run=client -o yaml \
        | kubectl apply -f - >/dev/null
    # O seed só entra no primeiro boot: seed ou certificado novos recriam o pod.
    SEED_CHECKSUM="$( (printf '%s\n' "${seed}"; kubectl -n "${NAMESPACE}" get secret openldap-tls \
        -o jsonpath='{.data.tls\.crt}') | sha256sum | cut -c1-16)"
    render k8s/sso/openldap.yaml | kubectl apply -f -
    wait_rollout openldap 180s
}

# Confere as entradas do diretório antes de mexer no cluster.
check_directory() {
    case "${DIRECTORY}" in
        openldap) ;;
        ad)
            local var
            for var in LDAP_URL LDAP_BIND_DN LDAP_BIND_PASSWORD LDAP_USERS_DN LDAP_GROUPS_DN LDAP_CA_FILE; do
                if [[ -z "${!var:-}" ]]; then
                    echo "DIRECTORY=ad needs ${var} (see the header of this script)" >&2
                    exit 2
                fi
            done
            if [[ ! -f "${LDAP_CA_FILE}" ]]; then
                echo "LDAP_CA_FILE not found: ${LDAP_CA_FILE}" >&2
                exit 2
            fi
            if [[ "${LDAP_URL}" != ldaps://* ]]; then
                echo "LDAP_URL must be ldaps:// (a simple bind over ldap:// sends the password in clear text)" >&2
                exit 2
            fi
            ;;
        *)
            echo "DIRECTORY must be openldap or ad (got '${DIRECTORY}')" >&2
            exit 2
            ;;
    esac
}

# Federação do Keycloak: o ConfigMap keycloak-ldap preenche os placeholders
# ${LDAP_*} do realm e o Secret keycloak-ldap leva a senha do bind e a CA.
setup_directory() {
    local dir bind_password
    case "${DIRECTORY}" in
        openldap)
            ensure_openldap
            LDAP_URL="ldaps://openldap.${NAMESPACE}.svc.cluster.local:636"
            LDAP_VENDOR=other
            LDAP_BIND_DN="cn=readonly,dc=tools,dc=local"
            LDAP_USERS_DN="ou=people,dc=tools,dc=local"
            LDAP_GROUPS_DN="ou=tools,dc=tools,dc=local"
            LDAP_USERNAME_ATTRIBUTE=uid
            LDAP_RDN_ATTRIBUTE=uid
            LDAP_UUID_ATTRIBUTE=entryUUID
            LDAP_USER_OBJECT_CLASSES="inetOrgPerson, organizationalPerson"
            LDAP_GROUP_OBJECT_CLASSES=groupOfNames
            # O OpenLDAP não tem o filtro de grupos aninhados do AD.
            LDAP_GROUPS_STRATEGY=LOAD_GROUPS_BY_MEMBER_ATTRIBUTE
            bind_password="$(secret_value ldap-readonly-password)"
            ;;
        ad)
            LDAP_VENDOR=ad
            LDAP_USERNAME_ATTRIBUTE="${LDAP_USERNAME_ATTRIBUTE:-sAMAccountName}"
            LDAP_RDN_ATTRIBUTE=cn
            LDAP_UUID_ATTRIBUTE=objectGUID
            LDAP_USER_OBJECT_CLASSES="person, organizationalPerson, user"
            LDAP_GROUP_OBJECT_CLASSES=group
            # Grupos aninhados, com o mesmo LDAP_MATCHING_RULE_IN_CHAIN do provider ldap.
            LDAP_GROUPS_STRATEGY=LOAD_GROUPS_BY_MEMBER_ATTRIBUTE_RECURSIVELY
            bind_password="${LDAP_BIND_PASSWORD}"
            ;;
    esac
    echo "[sso] Keycloak federates ${LDAP_URL} (users ${LDAP_USERS_DN}, groups ${LDAP_GROUPS_DN})"
    kubectl -n "${NAMESPACE}" create configmap keycloak-ldap \
        --from-literal=LDAP_VENDOR="${LDAP_VENDOR}" \
        --from-literal=LDAP_URL="${LDAP_URL}" \
        --from-literal=LDAP_BIND_DN="${LDAP_BIND_DN}" \
        --from-literal=LDAP_USERS_DN="${LDAP_USERS_DN}" \
        --from-literal=LDAP_GROUPS_DN="${LDAP_GROUPS_DN}" \
        --from-literal=LDAP_USERNAME_ATTRIBUTE="${LDAP_USERNAME_ATTRIBUTE}" \
        --from-literal=LDAP_RDN_ATTRIBUTE="${LDAP_RDN_ATTRIBUTE}" \
        --from-literal=LDAP_UUID_ATTRIBUTE="${LDAP_UUID_ATTRIBUTE}" \
        --from-literal=LDAP_USER_OBJECT_CLASSES="${LDAP_USER_OBJECT_CLASSES}" \
        --from-literal=LDAP_GROUP_OBJECT_CLASSES="${LDAP_GROUP_OBJECT_CLASSES}" \
        --from-literal=LDAP_GROUPS_STRATEGY="${LDAP_GROUPS_STRATEGY}" \
        --from-literal=GATEWAY_ADMINS_GROUP="${GATEWAY_ADMINS_GROUP}" \
        --dry-run=client -o yaml | kubectl apply -f - >/dev/null
    # Arquivo em vez de --from-literal, para a senha não aparecer no ps (mktemp -d cria com 0700).
    dir="$(mktemp -d)"
    printf '%s' "${bind_password}" > "${dir}/bind-password"
    if [[ "${DIRECTORY}" == "openldap" ]]; then
        kubectl -n "${NAMESPACE}" get secret openldap-tls -o jsonpath='{.data.ca\.crt}' | base64 -d > "${dir}/ca.pem"
    else
        cp "${LDAP_CA_FILE}" "${dir}/ca.pem"
    fi
    kubectl -n "${NAMESPACE}" create secret generic keycloak-ldap \
        --from-file=bind-password="${dir}/bind-password" --from-file=ca.pem="${dir}/ca.pem" \
        --dry-run=client -o yaml | kubectl apply -f - >/dev/null
    # Entra no checksum do pod do Keycloak: diretório, senha ou CA novos reimportam o realm.
    DIRECTORY_CHECKSUM="$( (kubectl -n "${NAMESPACE}" get configmap keycloak-ldap -o jsonpath='{.data}'
        cat "${dir}/bind-password" "${dir}/ca.pem"; printf '%s' "${SEED_CHECKSUM:-}") | sha256sum | cut -c1-16)"
    rm -rf "${dir}"
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
        kubectl -n "${NAMESPACE}" delete -f k8s/samples/routes-oauth.yaml --ignore-not-found
        # O cenário S3 troca o operator; v2 e s3 voltam para a ConfigMap.
        if operator_exists; then
            kubectl apply -f k8s/operator/deployment.yaml
        fi
        render k8s/s3/minio.yaml | kubectl delete --ignore-not-found -f -
        kubectl -n "${NAMESPACE}" delete secret minio-credentials --ignore-not-found
    fi
    render k8s/sso/keycloak.yaml | kubectl delete --ignore-not-found -f -
    render k8s/sso/openldap.yaml | kubectl delete --ignore-not-found -f -
    kubectl -n "${NAMESPACE}" delete configmap keycloak-realm-tools keycloak-ldap --ignore-not-found
    kubectl -n "${NAMESPACE}" delete secret openldap-seed keycloak-ldap --ignore-not-found
    wait_rollout dynamic-gateway 180s
    echo "[sso] Down. Secrets sso-secrets and openldap-tls kept (delete them by hand to rotate everything)."
}

if [[ "${MODE}" != "v2" && "${MODE}" != "s3" && "${MODE}" != "standalone" ]]; then
    echo "MODE must be v2, s3 or standalone (got '${MODE}')" >&2
    exit 2
fi
if [[ "${PROVIDER}" == "ldap" && "${DIRECTORY}" != "openldap" ]]; then
    echo "DIRECTORY only applies to the Keycloak federation; with PROVIDER=ldap the gateway reads" >&2
    echo "GATEWAY_SECURITY_LDAP_* (see SSO_CENARIO_CRD.md, 'Em outro ambiente')" >&2
    exit 2
fi
if [[ "${1:-}" != "down" ]]; then
    check_directory
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
if [[ ! -f "${GATEWAY_MANIFEST}" ]]; then
    echo "MODE=${MODE} has no manifest for PROVIDER=${PROVIDER} (${GATEWAY_MANIFEST})" >&2
    exit 2
fi
ensure_secrets

case "${PROVIDER}" in
    keycloak|oauth)
        setup_directory
        ensure_image "${KEYCLOAK_IMAGE}"
        kubectl -n "${NAMESPACE}" create configmap keycloak-realm-tools \
            --from-file=realm-tools.json=k8s/sso/realm-tools.json \
            --dry-run=client -o yaml | kubectl apply -f -
        # O checksum no pod template recria o Keycloak só quando o realm ou o
        # diretório mudam (modo dev: reiniciar apaga sessões e reimporta o realm).
        REALM_CHECKSUM="$( (sha256sum < k8s/sso/realm-tools.json; printf '%s' "${DIRECTORY_CHECKSUM}") \
            | sha256sum | cut -c1-16)"
        render k8s/sso/keycloak.yaml | kubectl apply -f -
        wait_rollout keycloak 600s
        ;;
    ldap)
        ensure_openldap
        ;;
    *)
        echo "PROVIDER must be keycloak, ldap or oauth (got '${PROVIDER}')" >&2
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
    if [[ "${PROVIDER}" == "oauth" ]]; then
        kubectl apply -f k8s/samples/routes-oauth.yaml
        TRY_PATH=/oauth/orders/headers
    fi
fi
kubectl apply -f k8s/nginx-lb/ >/dev/null
kubectl -n "${NAMESPACE}" rollout restart deployment/nginx-lb >/dev/null
wait_rollout nginx-lb 120s
wait_rollout dynamic-gateway 300s
wait_edge

echo
echo "[sso] Up with provider=${PROVIDER}, mode=${MODE} at http://localhost:${HOST_PORT}"
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
fi
