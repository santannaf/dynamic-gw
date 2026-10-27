#!/usr/bin/env bash
# E2E do v1 standalone com SSO (profile standalone-sso), pré-requisito:
#   make standalone-sso-up                 # provider keycloak (default)
#   PROVIDER=ldap make standalone-sso-up   # e rode este script com PROVIDER=ldap
# As rotas vêm de gateway/src/main/resources/application-standalone-sso.yaml:
#   /anything/** pública · /me/** qualquer autenticado · /httpbin/** tools-httpbin
#   /admin/** gateway-admin (role) ou tools-admins (grupo), com forward-token
set -uo pipefail

HOST_PORT="${HOST_PORT:-8080}"
BASE="http://localhost:${HOST_PORT}"
NAMESPACE="${NAMESPACE:-platform}"
PROVIDER="${PROVIDER:-keycloak}"
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

# Login de browser até a rota protegida; imprime o status final e deixa o corpo em ${WORK}/body.
login() {
    local user="$1" jar="$2" target="$3" page action csrf
    if [[ "${PROVIDER}" == "ldap" ]]; then
        curl -s -o /dev/null -c "${jar}" -b "${jar}" -H 'Accept: text/html' "${BASE}${target}"
        csrf="$(curl -s -c "${jar}" -b "${jar}" "${BASE}/_gateway/login" | grep -o 'name="_csrf" value="[^"]*"' \
            | sed -e 's/.*value="//' -e 's/"$//')"
        curl -s -L -c "${jar}" -b "${jar}" -o "${WORK}/body" -w '%{http_code}' \
            --data-urlencode "username=${user}" --data-urlencode "password=${PASSWORD}" --data-urlencode "_csrf=${csrf}" \
            "${BASE}/_gateway/login"
        return
    fi
    page="$(curl -s -L -c "${jar}" -b "${jar}" -H 'Accept: text/html' "${BASE}${target}")"
    action="$(printf '%s' "${page}" | grep -o 'action="[^"]*login-actions/authenticate[^"]*"' | head -1 \
        | sed -e 's/^action="//' -e 's/"$//' -e 's/&amp;/\&/g')"
    [[ -z "${action}" ]] && { echo "000"; return; }
    curl -s -L -c "${jar}" -b "${jar}" -H 'Accept: text/html' -o "${WORK}/body" -w '%{http_code}' \
        --data-urlencode "username=${user}" --data-urlencode "password=${PASSWORD}" "${action}"
}

header() { jq -r --arg h "$1" '.headers[$h][0] // empty' "${WORK}/body"; }
status() { curl -s -o "${WORK}/body" -w '%{http_code}' "$@"; }

echo "== v1 standalone + SSO (provider=${PROVIDER})"
check "gateway runs the standalone-sso and ${PROVIDER} profiles" "standalone-sso,${PROVIDER}" \
    "$(kubectl -n "${NAMESPACE}" get deployment dynamic-gateway \
        -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="SPRING_PROFILES_ACTIVE")].value}')"

echo "== anonymous"
check "public route stays open" "200" "$(status "${BASE}/anything/get")"
check "group route asks API callers for credentials" "401" "$(status "${BASE}/httpbin/get")"
check "authenticated route asks API callers for credentials" "401" "$(status "${BASE}/me/headers")"
check "browser navigation goes to the login" "302" \
    "$(curl -s -o /dev/null -w '%{http_code}' -H 'Accept: text/html' "${BASE}/httpbin/headers")"

echo "== alice (tools-httpbin)"
check "login lands on /httpbin" "200" "$(login alice "${WORK}/alice" /httpbin/headers)"
check "backend sees alice" "alice" "$(header X-Auth-Request-User)"
check "backend sees the group" "tools-httpbin" "$(header X-Auth-Request-Groups)"
check "any authenticated route" "200" "$(status -b "${WORK}/alice" "${BASE}/me/headers")"
check "admin route is not for alice" "403" "$(status -b "${WORK}/alice" "${BASE}/admin/headers")"

echo "== bob (no groups)"
check "bob reaches the authenticated route" "200" "$(login bob "${WORK}/bob" /me/headers)"
check "bob is not in tools-httpbin" "403" "$(status -b "${WORK}/bob" "${BASE}/httpbin/headers")"

echo "== carol (tools-admins)"
check "carol reaches /admin" "200" "$(login carol "${WORK}/carol" /admin/headers)"
if [[ "${PROVIDER}" == "keycloak" ]]; then
    check "forward-token relays the access token" "yes" \
        "$(header Authorization | grep -q '^Bearer ey' && echo yes || echo no)"
fi
check "carol reads the v1 route table" "4" \
    "$(curl -s -b "${WORK}/carol" "${BASE}/internal/routes" | jq '[.routes[] | select(.access != null or .id == "me-route")] | length')"

if [[ "${PROVIDER}" == "keycloak" ]]; then
    echo "== service account (tools-cli)"
    token="$(curl -s -d grant_type=client_credentials -d client_id=tools-cli \
        --data-urlencode "client_secret=$(secret tools-cli-client-secret)" \
        "${BASE}/auth/realms/tools/protocol/openid-connect/token" | jq -r .access_token)"
    check "bearer on an authenticated route" "200" "$(status -H "Authorization: Bearer ${token}" "${BASE}/me/headers")"
    check "backend sees the client id" "tools-cli" "$(header X-Auth-Request-Client-Id)"
    check "bearer without the group" "403" "$(status -H "Authorization: Bearer ${token}" "${BASE}/httpbin/headers")"
fi

echo "== audit trail"
kubectl -n "${NAMESPACE}" logs -l app.kubernetes.io/name=dynamic-gateway --since-time="${START}" --tail=-1 --prefix=false \
    | grep '^{"@timestamp"' > "${WORK}/audit.jsonl" || true
check "alice on httpbin-route is audited" "yes" \
    "$(jq -e -s 'any(.[]; .user.name == "alice" and .gateway.route.id == "httpbin-route" and .event.outcome == "success")' "${WORK}/audit.jsonl" >/dev/null && echo yes || echo no)"
check "bob's denial is audited" "yes" \
    "$(jq -e -s 'any(.[]; .user.name == "bob" and .gateway.route.id == "httpbin-route" and .event.reason == "forbidden")' "${WORK}/audit.jsonl" >/dev/null && echo yes || echo no)"
check "no token or password in the audit" "0" "$(grep -c -e 'eyJ' -e "${PASSWORD}" -e 'GW_SESSION=' "${WORK}/audit.jsonl")"

echo
echo "passed=${pass} failed=${fail}"
[[ ${fail} -eq 0 ]]
