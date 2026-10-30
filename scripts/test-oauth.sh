#!/usr/bin/env bash
# E2E do canal OAuth no k3d (pré-requisito: make oauth-up). Usa só curl + jq.
# O Keycloak local faz o papel do authorization server: o scope orders.read é
# criado pela Admin API como scope opcional do client tools-cli, então o mesmo
# client consegue token com e sem o scope.
set -uo pipefail

HOST_PORT="${HOST_PORT:-8080}"
BASE="http://localhost:${HOST_PORT}"
NAMESPACE="${NAMESPACE:-platform}"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

secret() { kubectl -n "${NAMESPACE}" get secret sso-secrets -o "jsonpath={.data.$1}" | base64 -d; }
CLI_SECRET="$(secret tools-cli-client-secret)"
ADMIN_PASSWORD="$(secret keycloak-admin-password)"
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

code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
header() { jq -r --arg h "$1" '.headers[$h][0] // empty' "${WORK}/body"; }

token() {
    curl -s -d grant_type=client_credentials -d client_id=tools-cli --data-urlencode "client_secret=${CLI_SECRET}" \
        "$@" "${BASE}/auth/realms/tools/protocol/openid-connect/token" | jq -r .access_token
}

echo "== scenario"
PROFILES="$(kubectl -n "${NAMESPACE}" get deployment dynamic-gateway \
    -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="SPRING_PROFILES_ACTIVE")].value}')"
check "gateway runs the oauth profile" "yes" "$([[ ",${PROFILES}," == *",oauth,"* ]] && echo yes || echo no)"

admin="$(curl -s -d grant_type=password -d client_id=admin-cli -d username=admin --data-urlencode "password=${ADMIN_PASSWORD}" \
    "${BASE}/auth/realms/master/protocol/openid-connect/token" | jq -r .access_token)"
api="${BASE}/auth/admin/realms/tools"
curl -s -o /dev/null -H "Authorization: Bearer ${admin}" -H 'Content-Type: application/json' -X POST "${api}/client-scopes" \
    -d '{"name":"orders.read","protocol":"openid-connect","attributes":{"include.in.token.scope":"true"}}'
scope_id="$(curl -s -H "Authorization: Bearer ${admin}" "${api}/client-scopes" | jq -r '.[] | select(.name == "orders.read") | .id')"
client_id="$(curl -s -H "Authorization: Bearer ${admin}" "${api}/clients?clientId=tools-cli" | jq -r '.[0].id')"
curl -s -o /dev/null -H "Authorization: Bearer ${admin}" -X PUT "${api}/clients/${client_id}/optional-client-scopes/${scope_id}"

echo "== anonymous"
curl -s -o /dev/null -D "${WORK}/headers" "${BASE}/oauth/orders/headers"
check "API call without token -> 401" "401" "$(head -1 "${WORK}/headers" | cut -d' ' -f2)"
check "401 carries the bearer challenge" "yes" \
    "$(grep -qi '^www-authenticate: Bearer' "${WORK}/headers" && echo yes || echo no)"
check "no cookie is ever set" "0" "$(grep -ci '^set-cookie' "${WORK}/headers")"
check "browser without token -> 401, no login" "401 " \
    "$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' -H 'Accept: text/html' "${BASE}/oauth/orders/headers")"
check "public route needs nothing" "200" "$(code "${BASE}/oauth/public/get")"
check "this channel has no logout" "404" "$(code "${BASE}/_gateway/logout")"

echo "== tokens"
with_scope="$(token -d scope=orders.read)"
without_scope="$(token)"
check "token with the scope -> 200" "200" \
    "$(curl -s -o "${WORK}/body" -w '%{http_code}' -H "Authorization: Bearer ${with_scope}" "${BASE}/oauth/orders/headers")"
check "backend sees the scope" "yes" \
    "$(header X-Auth-Request-Authorities | tr ',' '\n' | grep -qx 'scope:orders.read' && echo yes || echo no)"
check "backend sees the client id" "tools-cli" "$(header X-Auth-Request-Client-Id)"
check "token is not forwarded by default" "" "$(header Authorization)"
check "token without the scope -> 403" "403" "$(code -H "Authorization: Bearer ${without_scope}" "${BASE}/oauth/orders/headers")"
check "forwardToken relays the token" "yes" \
    "$(curl -s -o "${WORK}/body" -H "Authorization: Bearer ${with_scope}" "${BASE}/oauth/forward/headers"; \
       [[ "$(header Authorization)" == "Bearer ${with_scope}" ]] && echo yes || echo no)"
check "garbage token -> 401" "401" "$(code -H 'Authorization: Bearer garbage' "${BASE}/oauth/orders/headers")"
check "token from another realm -> 401" "401" "$(code -H "Authorization: Bearer ${admin}" "${BASE}/oauth/orders/headers")"
check "whoami shows the bearer identity" "bearer" \
    "$(curl -s -H "Authorization: Bearer ${with_scope}" "${BASE}/_gateway/whoami" | jq -r .method)"

echo "== audit trail"
kubectl -n "${NAMESPACE}" logs -l app.kubernetes.io/name=dynamic-gateway --since-time="${START}" --tail=-1 --prefix=false \
    | grep '^{"@timestamp"' > "${WORK}/audit.jsonl" || true
check "the matched scope is audited" "yes" \
    "$(jq -e -s 'any(.[]; .gateway.access.matched == "scope:orders.read" and .event.outcome == "success")' "${WORK}/audit.jsonl" >/dev/null && echo yes || echo no)"
check "the denial names the required scope" "yes" \
    "$(jq -e -s 'any(.[]; .event.reason == "forbidden" and (.gateway.access.required | index("scope:orders.read")))' "${WORK}/audit.jsonl" >/dev/null && echo yes || echo no)"
check "no token in the audit" "0" "$(grep -c 'eyJ' "${WORK}/audit.jsonl")"

echo
echo "passed=${pass} failed=${fail}"
[[ ${fail} -eq 0 ]]
