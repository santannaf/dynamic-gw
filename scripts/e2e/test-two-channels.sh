#!/usr/bin/env bash
# E2E de dois canais no mesmo gateway (pré-requisito: make two-channels-up, ou
# PROVIDER=keycloak-ldap make lab linux). As rotas de routes-sso.yaml são do canal
# keycloak; as de routes-two-channels.yaml (access.channel: ldap), do login direto no
# diretório pelo formulário do gateway, com a sessão no cookie GW_SESSION_LDAP.
set -uo pipefail

HOST_PORT="${HOST_PORT:-8080}"
BASE="http://localhost:${HOST_PORT}"
NAMESPACE="${NAMESPACE:-platform}"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

secret() { kubectl -n "${NAMESPACE}" get secret sso-secrets -o "jsonpath={.data.$1}" | base64 -d; }
PASSWORD="$(secret demo-password)"
START="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

pass=0
fail=0
check() {
    if [[ "$3" == "$2" ]]; then
        echo "  ok    $1"
        pass=$((pass + 1))
    else
        echo "  FAIL  $1 (expected '$2', got '$3')"
        fail=$((fail + 1))
    fi
}

# Formulário do gateway: visita a rota (salva o destino), abre o formulário, envia
# com o token CSRF e segue até a rota. Imprime o status final.
form_login() {
    local user="$1" jar="$2" target="$3" csrf
    curl -s -o /dev/null -c "${jar}" -b "${jar}" -H 'Accept: text/html' "${BASE}${target}"
    csrf="$(curl -s -c "${jar}" -b "${jar}" "${BASE}/_gateway/login" | grep -o 'name="_csrf" value="[^"]*"' \
        | sed -e 's/.*value="//' -e 's/"$//')"
    curl -s -L -c "${jar}" -b "${jar}" -o "${WORK}/body" -w '%{http_code}' \
        --data-urlencode "username=${user}" --data-urlencode "password=${PASSWORD}" --data-urlencode "_csrf=${csrf}" \
        "${BASE}/_gateway/login"
}

# Login no Keycloak: segue até o formulário dele, envia usuário/senha e volta pelo
# callback do gateway até a URL protegida. Imprime o status final.
keycloak_login() {
    local user="$1" jar="$2" target="$3" page action
    page="$(curl -s -L -c "${jar}" -b "${jar}" -H 'Accept: text/html' "${BASE}${target}")"
    action="$(printf '%s' "${page}" | grep -o 'action="[^"]*login-actions/authenticate[^"]*"' | head -1 \
        | sed -e 's/^action="//' -e 's/"$//' -e 's/&amp;/\&/g')"
    [[ -n "${action}" ]] || { echo "000"; return; }
    curl -s -L -c "${jar}" -b "${jar}" -H 'Accept: text/html' -o "${WORK}/body" -w '%{http_code}' \
        --data-urlencode "username=${user}" --data-urlencode "password=${PASSWORD}" "${action}"
}

header() { jq -r --arg h "$1" '.headers[$h][0] // empty' "${WORK}/body"; }
cookie_in() { awk -v name="$2" '$6 == name {found = "yes"} END {print found ? found : "no"}' "$1"; }
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
every_replica() {
    local codes=""
    for _ in 1 2 3 4 5 6; do codes+="$(status "$@") "; done
    printf '%s' "${codes}"
}

echo "== scenario"
PROFILES="$(kubectl -n "${NAMESPACE}" get deployment dynamic-gateway \
    -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="SPRING_PROFILES_ACTIVE")].value}')"
check "gateway runs the keycloak-ldap profile" "yes" \
    "$([[ ",${PROFILES}," == *",keycloak-ldap,"* ]] && echo yes || echo no)"
check "the ldap channel's routes are applied" "canal-ldap canal-ldap-aberta" \
    "$(kubectl -n "${NAMESPACE}" get gatewayroutes -o name | grep -o 'canal-ldap.*' | sort | paste -sd' ')"

echo "== the ldap channel (access.channel: ldap)"
check "browser navigation -> the gateway's login form" "302 ${BASE}/_gateway/login" \
    "$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' -H 'Accept: text/html' "${BASE}/canal-ldap/headers")"
check "API call without the ldap session -> 401" "401" "$(status "${BASE}/canal-ldap/headers")"
check "alice logs in on the form and lands on the route" "200" "$(form_login alice "${WORK}/alice-ldap" /canal-ldap/headers)"
check "backend sees the directory user" "alice" "$(header X-Auth-Request-User)"
check "and the groups of the app's OU" "tools-httpbin" "$(header X-Auth-Request-Groups)"
check "the session is in its own cookie" "yes no" \
    "$(cookie_in "${WORK}/alice-ldap" GW_SESSION_LDAP) $(cookie_in "${WORK}/alice-ldap" GW_SESSION)"
check "the cookie never reaches the backend" "" "$(header Cookie | grep -o 'GW_SESSION' || true)"
check "the session works on every replica" "200 200 200 200 200 200 " \
    "$(every_replica -b "${WORK}/alice-ldap" "${BASE}/canal-ldap/headers")"
check "whoami?channel=ldap" "alice" \
    "$(curl -s -b "${WORK}/alice-ldap" "${BASE}/_gateway/whoami?channel=ldap" | jq -r .user)"
check "bob (no group) logs in, but the route asks for tools-httpbin" "403" \
    "$(form_login bob "${WORK}/bob-ldap" /canal-ldap/headers)"
check "bob reaches the route open to anyone logged in" "200" \
    "$(status -b "${WORK}/bob-ldap" "${BASE}/canal-ldap-aberta/headers")"

echo "== one channel's session does not open the other's routes"
check "the ldap session is sent to the Keycloak login on a keycloak route" \
    "302 ${BASE}/_gateway/oauth2/authorization/keycloak" \
    "$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' -b "${WORK}/alice-ldap" -H 'Accept: text/html' \
        "${BASE}/sso/group/headers")"
check "carol logs in on Keycloak" "200" "$(keycloak_login carol "${WORK}/carol-kc" /sso/auth/headers)"
check "the Keycloak session is sent to the gateway's form on an ldap route" "302 ${BASE}/_gateway/login" \
    "$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' -b "${WORK}/carol-kc" -H 'Accept: text/html' \
        "${BASE}/canal-ldap-aberta/headers")"

echo "== both sessions: each route sees the caller of its channel"
cp "${WORK}/carol-kc" "${WORK}/both"
check "alice logs in on the form, next to carol's Keycloak session" "200" \
    "$(form_login alice "${WORK}/both" /canal-ldap/headers)"
curl -s -b "${WORK}/both" "${BASE}/sso/auth/headers" -o "${WORK}/body"
check "the keycloak route sees carol" "carol" "$(header X-Auth-Request-User)"
curl -s -b "${WORK}/both" "${BASE}/canal-ldap-aberta/headers" -o "${WORK}/body"
check "the ldap route sees alice" "alice" "$(header X-Auth-Request-User)"
check "whoami answers for the main channel, whoami?channel=ldap for the other" "carol alice" \
    "$(curl -s -b "${WORK}/both" "${BASE}/_gateway/whoami" | jq -r .user) $(curl -s -b "${WORK}/both" \
        "${BASE}/_gateway/whoami?channel=ldap" | jq -r .user)"

echo "== whoever operates the gateway comes in through either channel"
check "dave (gateway-admins) on the form reads /internal/routes" "200" \
    "$(form_login dave "${WORK}/dave-ldap" /internal/routes)"
check "alice on the form does not" "403" "$(status -b "${WORK}/alice-ldap" "${BASE}/internal/routes")"

echo "== logout ends both sessions"
cp "${WORK}/both" "${WORK}/both-copy"
check "logout goes to the Keycloak logout" "302" "$(status -b "${WORK}/both" -c "${WORK}/both" "${BASE}/_gateway/logout")"
check "a copy of the ldap cookie stops working on every replica" "401 401 401 401 401 401 " \
    "$(every_replica -b "${WORK}/both-copy" "${BASE}/canal-ldap/headers")"
check "and so does the copy of the Keycloak cookie" "401 401 401 401 401 401 " \
    "$(every_replica -b "${WORK}/both-copy" "${BASE}/sso/auth/headers")"
cp "${WORK}/alice-ldap" "${WORK}/alice-ldap-copy"
check "with only the ldap session, the logout ends on the gateway's form" "302 ${BASE}/_gateway/login?logout" \
    "$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' -b "${WORK}/alice-ldap" "${BASE}/_gateway/logout")"
check "and its copy stops working" "401" "$(status -b "${WORK}/alice-ldap-copy" "${BASE}/canal-ldap/headers")"

echo "== audit trail"
kubectl -n "${NAMESPACE}" logs -l app.kubernetes.io/name=dynamic-gateway --since-time="${START}" --tail=-1 --prefix=false \
    | grep '^{"@timestamp"' > "${WORK}/audit.jsonl" || true
check "the ldap route's access names the directory user" "yes" \
    "$(jq -e -s 'any(.[]; .user.name == "alice" and .gateway.route.id == "canal-ldap" and .event.outcome == "success")' \
        "${WORK}/audit.jsonl" >/dev/null && echo yes || echo no)"
check "and bob's denial there" "yes" \
    "$(jq -e -s 'any(.[]; .user.name == "bob" and .gateway.route.id == "canal-ldap" and .event.reason == "forbidden")' \
        "${WORK}/audit.jsonl" >/dev/null && echo yes || echo no)"
check "no token, password or session cookie in the audit" "0" \
    "$(grep -c -e 'eyJ' -e "${PASSWORD}" -e 'GW_SESSION=' -e 'GW_SESSION_LDAP=' "${WORK}/audit.jsonl")"

echo
echo "passed=${pass} failed=${fail}"
[[ "${fail}" -eq 0 ]]
