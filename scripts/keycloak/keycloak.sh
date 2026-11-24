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
#
# A tela de login usa o tema de k8s/sso/theme/tools (ConfigMap keycloak-theme-tools). Mudar
# só o tema reinicia o Keycloak sem zerar o banco.
#
# MICROSOFT escolhe para onde vai o botão "Login com Microsoft" (provedor microsoft do realm):
#   sim (default)  realm microsoft-sim neste mesmo Keycloak, com os usuários do diretório do lab
#   entra          o Entra ID de um App Registration. Obrigatórios (o segredo nunca vai para o repo):
#       MICROSOFT_TENANT_ID=...      ID do tenant (Directory ID)
#       MICROSOFT_CLIENT_ID=...      Application (client) ID
#       MICROSOFT_CLIENT_SECRET=...  client secret do App Registration
#     Redirect a cadastrar no App Registration: http://localhost:<HOST_PORT>/auth/realms/tools/broker/microsoft/endpoint
#   MICROSOFT_DEFAULT_SCOPE (openid profile email offline_access): escopos do login com Microsoft. O
#     Keycloak guarda o token que vem dele (storeToken), que o gateway troca para o OBO; com Entra, ponha
#     o escopo da API do gateway (api://<app>/<escopo>) para o token sair com a aud do gateway.
# A conta da Microsoft é associada ao usuário do AD com o mesmo e-mail; sem ele, o login é recusado.
#
# Depois que o Keycloak sobe, o `up` configura pela API de administração o que o JSON do realm não
# expressa sem apagar os client scopes padrão: um client scope opcional de mesmo nome para cada
# audience de TOKEN_AUDIENCES (httpbin-api), que o gateway pede na troca de token (access.audience),
# e a permissão da troca V1 do tools-gateway no provedor microsoft (passo 1 do access.obo).
#
# KUBE_CONTEXT e NAMESPACE sobem o mesmo Keycloak, com o diretório e o banco, em outro
# cluster, num namespace que já existe:
#   KUBE_CONTEXT=<contexto> NAMESPACE=<namespace> scripts/keycloak/keycloak.sh up
# Fora do k3d do lab, as imagens vêm do registry (por isso não há KEYCLOAK_DB=oracle, cuja
# imagem só existe local), o Ingress do /auth fica de fora, e o issuer continua
# http://localhost:<HOST_PORT>/auth: o navegador chega ao Keycloak por
#   kubectl --context <contexto> -n <namespace> port-forward svc/keycloak <HOST_PORT>:8080
# O OpenLDAP do lab só roda como root, e um namespace com Pod Security restricted recusa o
# pod: ali, use scripts/keycloak/keycloak-dev.sh (só Keycloak e PostgreSQL, sem diretório).
set -euo pipefail
LOG_PREFIX=keycloak
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"

KEYCLOAK_BASE_IMAGE="quay.io/keycloak/keycloak:26.8.2"
KEYCLOAK_DB="${KEYCLOAK_DB:-postgres}"
DIRECTORY="${DIRECTORY:-openldap}"
GATEWAY_ADMINS_GROUP="${GATEWAY_ADMINS_GROUP:-gateway-admins}"
MICROSOFT="${MICROSOFT:-sim}"
MICROSOFT_DEFAULT_SCOPE="${MICROSOFT_DEFAULT_SCOPE:-openid profile email offline_access}"
TOKEN_AUDIENCES="${TOKEN_AUDIENCES:-httpbin-api}"
THEME_DIR="k8s/sso/theme/tools/login"
DB="${ROOT}/scripts/db/${KEYCLOAK_DB}.sh"

check() {
    if [[ "${KEYCLOAK_DB}" != "postgres" && "${KEYCLOAK_DB}" != "oracle" ]]; then
        echo "KEYCLOAK_DB must be postgres or oracle (got '${KEYCLOAK_DB}')" >&2
        exit 2
    fi
    if [[ "${KEYCLOAK_DB}" == "oracle" ]] && ! lab_cluster; then
        echo "KEYCLOAK_DB=oracle runs keycloak-oracle:local, an image that only exists in the lab's k3d" >&2
        exit 2
    fi
    case "${MICROSOFT}" in
        sim) ;;
        entra)
            local var
            for var in MICROSOFT_TENANT_ID MICROSOFT_CLIENT_ID MICROSOFT_CLIENT_SECRET; do
                if [[ -z "${!var:-}" ]]; then
                    echo "MICROSOFT=entra needs ${var} (see the header of scripts/keycloak/keycloak.sh)" >&2
                    exit 2
                fi
            done
            ;;
        *)
            echo "MICROSOFT must be sim or entra (got '${MICROSOFT}')" >&2
            exit 2
            ;;
    esac
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
                -o jsonpath='{.spec.template.metadata.annotations.platform\.yyy\.xxx/seed-checksum}')"
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

# Valores do provedor microsoft do realm (placeholders ${MICROSOFT_*}). No simulado, o
# navegador chega ao realm microsoft-sim por localhost, e o Keycloak busca o token e as
# chaves pelo endereço interno, porque localhost dentro do pod é o próprio pod.
setup_microsoft() {
    local issuer authorize token jwks client secret previous
    if [[ "${MICROSOFT}" == "entra" ]]; then
        local login="https://login.microsoftonline.com/${MICROSOFT_TENANT_ID}"
        issuer="${login}/v2.0"
        authorize="${login}/oauth2/v2.0/authorize"
        token="${login}/oauth2/v2.0/token"
        jwks="${login}/discovery/v2.0/keys"
        client="${MICROSOFT_CLIENT_ID}"
        secret="${MICROSOFT_CLIENT_SECRET}"
    else
        local internal="http://keycloak.${NAMESPACE}.svc.cluster.local:8080/auth/realms/microsoft-sim/protocol/openid-connect"
        issuer="http://localhost:${HOST_PORT}/auth/realms/microsoft-sim"
        authorize="${issuer}/protocol/openid-connect/auth"
        token="${internal}/token"
        jwks="${internal}/certs"
        client="tools-broker"
        previous="$(kubectl -n "${NAMESPACE}" get configmap keycloak-microsoft -o jsonpath='{.data.MICROSOFT_MODE}' 2>/dev/null || true)"
        secret=""
        if [[ "${previous}" == "sim" ]]; then
            secret="$(kubectl -n "${NAMESPACE}" get secret keycloak-microsoft -o jsonpath='{.data.client-secret}' 2>/dev/null | base64 -d || true)"
        fi
        [[ -n "${secret}" ]] || secret="$(openssl rand -hex 24)"
    fi
    log "Login com Microsoft: ${MICROSOFT} (${issuer})"
    kubectl -n "${NAMESPACE}" create configmap keycloak-microsoft \
        --from-literal=MICROSOFT_MODE="${MICROSOFT}" \
        --from-literal=MICROSOFT_CLIENT_ID="${client}" \
        --from-literal=MICROSOFT_AUTHORIZATION_URL="${authorize}" \
        --from-literal=MICROSOFT_TOKEN_URL="${token}" \
        --from-literal=MICROSOFT_JWKS_URL="${jwks}" \
        --from-literal=MICROSOFT_ISSUER="${issuer}" \
        --from-literal=MICROSOFT_DEFAULT_SCOPE="${MICROSOFT_DEFAULT_SCOPE}" \
        --dry-run=client -o yaml | kubectl apply -f - >/dev/null
    kubectl -n "${NAMESPACE}" create secret generic keycloak-microsoft \
        --from-literal=client-secret="${secret}" \
        --dry-run=client -o yaml | kubectl apply -f - >/dev/null
    MICROSOFT_CHECKSUM="$(printf '%s|%s|%s|%s|%s' "${MICROSOFT}" "${client}" "${issuer}" "${secret}" \
        "${MICROSOFT_DEFAULT_SCOPE}" | sha256sum | cut -c1-16)"
}

# O tema não entra no checksum do realm: mudar só a tela reinicia o Keycloak, sem zerar o banco.
publish_theme() {
    local files=("${THEME_DIR}/theme.properties" "${THEME_DIR}/resources/css/tools.css"
        "${THEME_DIR}/resources/img/logo.svg" "${THEME_DIR}/messages/messages_pt_BR.properties"
        gateway/src/main/resources/static/favicon.ico "${THEME_DIR}/resources/img/microsoft.svg"
        "${THEME_DIR}/login.ftl")
    kubectl -n "${NAMESPACE}" create configmap keycloak-theme-tools \
        --from-file=theme.properties="${files[0]}" \
        --from-file=tools.css="${files[1]}" \
        --from-file=logo.svg="${files[2]}" \
        --from-file=messages_pt_BR.properties="${files[3]}" \
        --from-file=favicon.ico="${files[4]}" \
        --from-file=microsoft.svg="${files[5]}" \
        --from-file=login.ftl="${files[6]}" \
        --dry-run=client -o yaml | kubectl apply -f - >/dev/null
    THEME_CHECKSUM="$(cat "${files[@]}" | sha256sum | cut -c1-16)"
}

# Idempotente: roda a cada `up`, com o realm recém-importado ou não.
configure_realm() {
    local base="http://localhost:${HOST_PORT}/auth" token api gateway audience scope permissions permission resource \
        management authz policy_name policy exchange_scope attempt
    if ! lab_cluster; then
        forward_keycloak
        base="http://localhost:${KEYCLOAK_PORT}/auth"
    fi
    for attempt in $(seq 1 60); do
        curl -sf -o /dev/null "${base}/realms/tools/.well-known/openid-configuration" && break
        sleep 2
    done
    token="$(curl -sf -d grant_type=password -d client_id=admin-cli -d username=admin \
        --data-urlencode "password=$(secret_value keycloak-admin-password)" \
        "${base}/realms/master/protocol/openid-connect/token" | jq -r .access_token)"
    kc() { curl -sf -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json' "$@"; }
    api="${base}/admin/realms/tools"
    gateway="$(kc "${api}/clients?clientId=tools-gateway" | jq -r '.[0].id')"

    for audience in ${TOKEN_AUDIENCES}; do
        scope="$(kc "${api}/client-scopes" | jq -r --arg n "${audience}" '.[] | select(.name == $n) | .id')"
        if [[ -z "${scope}" ]]; then
            jq -n --arg n "${audience}" '{name: $n, protocol: "openid-connect",
                attributes: {"include.in.token.scope": "true", "display.on.consent.screen": "false"},
                protocolMappers: [{name: ("audience: " + $n), protocol: "openid-connect",
                    protocolMapper: "oidc-audience-mapper",
                    config: {"included.client.audience": $n, "access.token.claim": "true",
                        "id.token.claim": "false", "introspection.token.claim": "true"}}]}' \
                | kc -X POST "${api}/client-scopes" --data @- >/dev/null
            scope="$(kc "${api}/client-scopes" | jq -r --arg n "${audience}" '.[] | select(.name == $n) | .id')"
        fi
        kc -X PUT "${api}/clients/${gateway}/optional-client-scopes/${scope}" >/dev/null
    done

    permissions="$(kc -X PUT "${api}/identity-provider/instances/microsoft/management/permissions" -d '{"enabled": true}')"
    permission="$(jq -r '.scopePermissions["token-exchange"]' <<<"${permissions}")"
    resource="$(jq -r .resource <<<"${permissions}")"
    management="$(kc "${api}/clients?clientId=realm-management" | jq -r '.[0].id')"
    authz="${api}/clients/${management}/authz/resource-server"
    policy_name="tools-gateway exchanges for microsoft"
    policy="$(kc "${authz}/policy?name=$(jq -rn --arg n "${policy_name}" '$n | @uri')" \
        | jq -r --arg n "${policy_name}" '.[] | select(.name == $n) | .id')"
    if [[ -z "${policy}" ]]; then
        policy="$(jq -n --arg n "${policy_name}" --arg c "${gateway}" '{name: $n, logic: "POSITIVE", clients: [$c]}' \
            | kc -X POST "${authz}/policy/client" --data @- | jq -r .id)"
    fi
    exchange_scope="$(kc "${authz}/permission/${permission}/scopes" | jq -r '.[0].id')"
    kc "${authz}/permission/scope/${permission}" \
        | jq --arg r "${resource}" --arg s "${exchange_scope}" --arg p "${policy}" \
            '. + {resources: [$r], scopes: [$s], policies: [$p]}' \
        | kc -X PUT "${authz}/permission/scope/${permission}" --data @- >/dev/null
    log "Token exchange: audiences ${TOKEN_AUDIENCES} for tools-gateway, and tools-gateway may exchange for microsoft"
}

# Fora do k3d, a API de administração vai por um port-forward numa porta livre, que
# fecha quando o script termina. Sem a função kubectl: o kill do $! tem que pegar o
# próprio port-forward.
forward_keycloak() {
    local output attempt
    output="$(mktemp)"
    command kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" port-forward svc/keycloak :8080 >"${output}" 2>&1 &
    trap "kill $! 2>/dev/null; rm -f '${output}'" EXIT
    for attempt in $(seq 1 30); do
        KEYCLOAK_PORT="$(sed -n 's/^Forwarding from 127\.0\.0\.1:\([0-9]*\) .*/\1/p' "${output}")"
        [[ -n "${KEYCLOAK_PORT}" ]] && return
        sleep 1
    done
    echo "port-forward to svc/keycloak failed: $(cat "${output}")" >&2
    exit 1
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
    setup_microsoft
    publish_theme
    local realms=(--from-file=realm-tools.json=k8s/sso/realm-tools.json)
    if [[ "${MICROSOFT}" == "sim" ]]; then
        realms+=(--from-file=realm-microsoft-sim.json=k8s/sso/realm-microsoft-sim.json)
    fi
    kubectl -n "${NAMESPACE}" create configmap keycloak-realm-tools "${realms[@]}" \
        --dry-run=client -o yaml | kubectl apply -f - >/dev/null
    # Trocar de banco também reimporta: o realm passa a viver no banco novo.
    REALM_CHECKSUM="$( (sha256sum < k8s/sso/realm-tools.json; sha256sum < k8s/sso/realm-microsoft-sim.json
        printf '%s%s' "${DIRECTORY_CHECKSUM}" "${MICROSOFT_CHECKSUM}"
        kubectl -n "${NAMESPACE}" get configmap keycloak-db -o jsonpath='{.data}') | sha256sum | cut -c1-16)"
    if kubectl -n "${NAMESPACE}" get deployment keycloak >/dev/null 2>&1; then
        local deployed
        deployed="$(kubectl -n "${NAMESPACE}" get deployment keycloak \
            -o jsonpath='{.spec.template.metadata.annotations.platform\.yyy\.xxx/realm-checksum}')"
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
    if lab_cluster; then
        render k8s/sso/keycloak-ingress.yaml | kubectl apply -f - >/dev/null
    fi
    wait_rollout deployment/keycloak 600s
    configure_realm
}

reset() {
    KEYCLOAK_DB="$(kubectl -n "${NAMESPACE}" get configmap keycloak-db -o jsonpath='{.data.KC_DB}')"
    DB="${ROOT}/scripts/db/${KEYCLOAK_DB}.sh"
    stop
    "${DB}" reset
    kubectl -n "${NAMESPACE}" scale deployment/keycloak --replicas=1 >/dev/null
    wait_rollout deployment/keycloak 600s
    configure_realm
}

down() {
    render k8s/sso/keycloak.yaml | kubectl delete --ignore-not-found -f -
    render k8s/sso/keycloak-ingress.yaml | kubectl delete --ignore-not-found -f -
    kubectl -n "${NAMESPACE}" delete configmap keycloak-realm-tools keycloak-ldap keycloak-db keycloak-theme-tools \
        keycloak-microsoft --ignore-not-found
    kubectl -n "${NAMESPACE}" delete secret keycloak-ldap keycloak-db keycloak-microsoft --ignore-not-found
}

case "${1:-}" in
    up) up ;;
    reset) reset ;;
    check) check ;;
    down) down ;;
    *) echo "usage: $0 up|reset|check|down" >&2; exit 2 ;;
esac
