#!/usr/bin/env bash
# Keycloak do lab (k8s/sso/keycloak.yaml): modo produção, banco de scripts/db/
# e o realm tools de k8s/sso/realm-tools.json, que federa o diretório.
#
# KEYCLOAK_DB escolhe o banco:
#   postgres (default)  scripts/db/postgres.sh, imagem oficial do Keycloak
#   oracle              scripts/db/oracle.sh (Oracle 26ai Free), imagem keycloak-oracle:local
#                       montada de docker/keycloak-oracle/ com o driver JDBC da Oracle
#
#   scripts/keycloak/keycloak.sh up      # diretório + banco + realm + Keycloak
#   scripts/keycloak/keycloak.sh reset   # zera o banco: o realm volta a ser o do JSON e todas as sessões caem
#   scripts/keycloak/keycloak.sh check   # só confere as variáveis do diretório, sem mexer no cluster
#   scripts/keycloak/keycloak.sh down    # remove o Keycloak e a configuração dele (banco e diretório têm o próprio down)
#
# O Keycloak só importa o realm que ainda não existe no banco. Quando o JSON, o
# diretório ou o banco mudam, o `up` zera o banco antes de subir, e o realm volta
# a ser o do JSON.
#
# DIRECTORY escolhe o diretório federado, sempre por LDAPS e somente leitura:
#   openldap (default)  OpenLDAP do lab no lugar do AD (scripts/ldap/openldap.sh), com certificado da CA do lab
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
LOG_PREFIX=keycloak
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"

KEYCLOAK_BASE_IMAGE="quay.io/keycloak/keycloak:26.1"
KEYCLOAK_DB="${KEYCLOAK_DB:-postgres}"
DIRECTORY="${DIRECTORY:-openldap}"
GATEWAY_ADMINS_GROUP="${GATEWAY_ADMINS_GROUP:-gateway-admins}"
DB="${ROOT}/scripts/db/${KEYCLOAK_DB}.sh"

check() {
    if [[ "${KEYCLOAK_DB}" != "postgres" && "${KEYCLOAK_DB}" != "oracle" ]]; then
        echo "KEYCLOAK_DB must be postgres or oracle (got '${KEYCLOAK_DB}')" >&2
        exit 2
    fi
    case "${DIRECTORY}" in
        openldap) ;;
        ad)
            local var
            for var in LDAP_URL LDAP_BIND_DN LDAP_BIND_PASSWORD LDAP_USERS_DN LDAP_GROUPS_DN LDAP_CA_FILE; do
                if [[ -z "${!var:-}" ]]; then
                    echo "DIRECTORY=ad needs ${var} (see the header of scripts/keycloak/keycloak.sh)" >&2
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

# Federação: o ConfigMap keycloak-ldap preenche os placeholders ${LDAP_*} do realm
# e o Secret keycloak-ldap leva a senha do bind e a CA.
setup_directory() {
    local dir bind_password seed_checksum=""
    case "${DIRECTORY}" in
        openldap)
            "${ROOT}/scripts/ldap/openldap.sh" up
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
            # Diretório recriado do seed tem usuários com ids novos: entra no checksum do realm.
            seed_checksum="$(kubectl -n "${NAMESPACE}" get deployment openldap \
                -o jsonpath='{.spec.template.metadata.annotations.platform\.saca\.pags/seed-checksum}')"
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
    log "Federating ${LDAP_URL} (users ${LDAP_USERS_DN}, groups ${LDAP_GROUPS_DN})"
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
    DIRECTORY_CHECKSUM="$( (kubectl -n "${NAMESPACE}" get configmap keycloak-ldap -o jsonpath='{.data}'
        cat "${dir}/bind-password" "${dir}/ca.pem"; printf '%s' "${seed_checksum}") | sha256sum | cut -c1-16)"
    rm -rf "${dir}"
}

# Oracle: o driver JDBC não vem no Keycloak, então a imagem é montada aqui.
ensure_keycloak_image() {
    if [[ "${KEYCLOAK_DB}" == "oracle" ]]; then
        KEYCLOAK_IMAGE="keycloak-oracle:local"
        log "Building ${KEYCLOAK_IMAGE} (Keycloak + Oracle JDBC driver)"
        docker build -q -t "${KEYCLOAK_IMAGE}" docker/keycloak-oracle >/dev/null
        log "Importing ${KEYCLOAK_IMAGE} into k3d cluster '${CLUSTER_NAME}'"
        k3d image import "${KEYCLOAK_IMAGE}" -c "${CLUSTER_NAME}" >/dev/null
    else
        KEYCLOAK_IMAGE="${KEYCLOAK_BASE_IMAGE}"
        ensure_image "${KEYCLOAK_IMAGE}"
    fi
}

stop() {
    kubectl -n "${NAMESPACE}" scale deployment/keycloak --replicas=0 >/dev/null
    kubectl -n "${NAMESPACE}" wait --for=delete pod -l app.kubernetes.io/name=keycloak --timeout=180s >/dev/null 2>&1 || true
}

up() {
    check
    ensure_sso_secrets
    setup_directory
    "${DB}" up
    ensure_keycloak_image
    kubectl -n "${NAMESPACE}" create configmap keycloak-realm-tools \
        --from-file=realm-tools.json=k8s/sso/realm-tools.json \
        --dry-run=client -o yaml | kubectl apply -f - >/dev/null
    # Trocar de banco também reimporta: o realm passa a viver no banco novo.
    REALM_CHECKSUM="$( (sha256sum < k8s/sso/realm-tools.json; printf '%s' "${DIRECTORY_CHECKSUM}"
        kubectl -n "${NAMESPACE}" get configmap keycloak-db -o jsonpath='{.data}') | sha256sum | cut -c1-16)"
    if kubectl -n "${NAMESPACE}" get deployment keycloak >/dev/null 2>&1; then
        local deployed
        deployed="$(kubectl -n "${NAMESPACE}" get deployment keycloak \
            -o jsonpath='{.spec.template.metadata.annotations.platform\.saca\.pags/realm-checksum}')"
        if [[ "${deployed}" != "${REALM_CHECKSUM}" ]]; then
            log "Realm, directory or database changed: resetting the database so the realm is imported again"
            stop
            "${DB}" reset
        elif "${DB}" empty; then
            # Banco novo com o Keycloak no ar: só um boot cria as tabelas e importa o realm.
            log "Empty database: restarting Keycloak to import the realm"
            kubectl -n "${NAMESPACE}" rollout restart deployment/keycloak >/dev/null
        fi
    fi
    render k8s/sso/keycloak.yaml | kubectl apply -f - >/dev/null
    wait_rollout deployment/keycloak 600s
}

reset() {
    KEYCLOAK_DB="$(kubectl -n "${NAMESPACE}" get configmap keycloak-db -o jsonpath='{.data.KC_DB}')"
    DB="${ROOT}/scripts/db/${KEYCLOAK_DB}.sh"
    stop
    "${DB}" reset
    kubectl -n "${NAMESPACE}" scale deployment/keycloak --replicas=1 >/dev/null
    wait_rollout deployment/keycloak 600s
}

down() {
    render k8s/sso/keycloak.yaml | kubectl delete --ignore-not-found -f -
    kubectl -n "${NAMESPACE}" delete configmap keycloak-realm-tools keycloak-ldap keycloak-db --ignore-not-found
    kubectl -n "${NAMESPACE}" delete secret keycloak-ldap keycloak-db --ignore-not-found
}

case "${1:-}" in
    up) up ;;
    reset) reset ;;
    check) check ;;
    down) down ;;
    *) echo "usage: $0 up|reset|check|down" >&2; exit 2 ;;
esac
