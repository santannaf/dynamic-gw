#!/usr/bin/env bash
# E2E dos modelos de integração de front-end no k3d (pré-requisito: make sso-up).
# Cada seção leva o número do modelo em GATEWAY_DE_BORDA.md, e o go-httpbin
# faz o papel de Front-End e de BFF. Os modelos 5, 7 e 8 são regras de arquitetura
# e não têm o que testar aqui. O script liga front-origins só durante o teste.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

HOST_PORT="${HOST_PORT:-8080}"
BASE="http://localhost:${HOST_PORT}"
FRONT="http://front.localhost:${HOST_PORT}"
NAMESPACE="${NAMESPACE:-platform}"
WORK="$(mktemp -d)"

secret() { kubectl -n "${NAMESPACE}" get secret sso-secrets -o "jsonpath={.data.$1}" | base64 -d; }
PASSWORD="$(secret demo-password)"
CLI_SECRET="$(secret tools-cli-client-secret)"

cleanup() {
    kubectl -n "${NAMESPACE}" delete -f k8s/samples/network-policy-backend.yaml --ignore-not-found >/dev/null
    kubectl -n "${NAMESPACE}" set env deployment/dynamic-gateway GATEWAY_SECURITY_FRONT_ORIGINS_0- >/dev/null
    kubectl -n "${NAMESPACE}" rollout status deployment/dynamic-gateway --timeout=180s >/dev/null
    rm -rf "${WORK}"
}
trap cleanup EXIT

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
response_header() { grep -i "^$1:" "${WORK}/headers" | tr -d '\r' | cut -d' ' -f2; }

# Logo depois do rollout, o cache de DNS do nginx-lb ainda aponta para pods que saíram.
wait_edge() {
    local ok=0 status
    for _ in $(seq 1 120); do
        status="$(code "${BASE}/_gateway/whoami" || true)"
        if [[ "${status}" =~ ^[1-4][0-9][0-9]$ ]]; then ok=$((ok + 1)); else ok=0; fi
        [[ "${ok}" -ge 15 ]] && return 0
        sleep 1
    done
}

# Abre $3 no navegador, preenche o formulário do Keycloak e devolve a URL final.
browser_login() {
    local user="$1" jar="$2" target="$3" page action
    page="$(curl -s -L -c "${jar}" -b "${jar}" -H 'Accept: text/html' "${target}")"
    action="$(printf '%s' "${page}" | grep -o 'action="[^"]*login-actions/authenticate[^"]*"' | head -1 \
        | sed -e 's/^action="//' -e 's/"$//' -e 's/&amp;/\&/g')"
    [[ -n "${action}" ]] || return 1
    curl -s -L -c "${jar}" -b "${jar}" -H 'Accept: text/html' -o "${WORK}/body" -w '%{url_effective}' \
        --data-urlencode "username=${user}" --data-urlencode "password=${PASSWORD}" "${action}"
}

kubectl -n "${NAMESPACE}" set env deployment/dynamic-gateway "GATEWAY_SECURITY_FRONT_ORIGINS_0=${FRONT}" >/dev/null
kubectl -n "${NAMESPACE}" rollout status deployment/dynamic-gateway --timeout=180s >/dev/null
wait_edge

echo "== modelo 1: estático + BFF (front em outra origem)"
curl -s -o /dev/null -D "${WORK}/headers" -H "Origin: ${FRONT}" "${BASE}/sso/group/get"
check "AJAX without session -> 401" "401" "$(head -1 "${WORK}/headers" | cut -d' ' -f2)"
check "the front can read the 401" "${FRONT}" "$(response_header access-control-allow-origin)"
check "preflight has no credentials and passes" "200" \
    "$(code -X OPTIONS -H "Origin: ${FRONT}" -H 'Access-Control-Request-Method: POST' "${BASE}/sso/rw/post")"
check "preflight from another origin -> 403" "403" \
    "$(code -X OPTIONS -H 'Origin: http://evil.localhost' -H 'Access-Control-Request-Method: POST' "${BASE}/sso/rw/post")"
check "return target outside the list -> 400" "400" \
    "$(code --get --data-urlencode 'returnTo=http://evil.localhost/' "${BASE}/_gateway/login/start")"
check "login returns to the front" "${FRONT}/pedidos?id=1" \
    "$(browser_login alice "${WORK}/alice" "${BASE}/_gateway/login/start?returnTo=${FRONT}%2Fpedidos%3Fid%3D1")"
curl -s -o "${WORK}/body" -D "${WORK}/headers" -b "${WORK}/alice" -H "Origin: ${FRONT}" "${BASE}/sso/group/headers"
check "AJAX with session reaches the BFF" "200" "$(head -1 "${WORK}/headers" | cut -d' ' -f2)"
check "the front can read the response" "true" "$(response_header access-control-allow-credentials)"
check "the BFF sees who is calling" "alice" "$(header X-Auth-Request-User)"

echo "== modelo 2: SSR puro"
check "navigation without session -> login -> page" "${BASE}/sso/group/headers" \
    "$(browser_login carol "${WORK}/carol" "${BASE}/sso/group/headers")"
check "the app receives the permissions" "yes" \
    "$(header X-Auth-Request-Authorities | tr ',' '\n' | grep -qx 'group:tools-httpbin' && echo yes || echo no)"
check "the app never receives the session cookie" "" "$(header Cookie | grep -o 'GW_SESSION' || true)"

echo "== modelo 3: SSR ou híbrido, AJAX na própria app"
check "AJAX with session -> 200" "200" "$(code -b "${WORK}/alice" -H 'Accept: application/json' "${BASE}/sso/group/get")"
check "AJAX without session -> 401, not a redirect" "401 " \
    "$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' -H 'Accept: application/json' "${BASE}/sso/group/get")"
check "fetch asking for HTML -> 401, not a redirect" "401 " \
    "$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' -H 'Accept: text/html' -H 'Sec-Fetch-Mode: cors' "${BASE}/sso/group/get")"

echo "== modelo 4: híbrido + BFF separado"
check "same session on the page route" "200" "$(code -b "${WORK}/alice" -H 'Accept: text/html' "${BASE}/sso/group/get")"
check "same session on the BFF route" "200" "$(code -b "${WORK}/alice" "${BASE}/sso/rw/get")"

echo "== modelo 6: navegador fora da borda"
direct() {
    kubectl -n "${NAMESPACE}" exec deployment/nginx-lb -- \
        wget -q -T 4 -O /dev/null http://go-httpbin.platform.svc.cluster.local:8080/status/200 >/dev/null 2>&1 \
        && echo reached || echo blocked
}
check "without the policy another pod reaches the backend" "reached" "$(direct)"
kubectl apply -f k8s/samples/network-policy-backend.yaml >/dev/null
sleep 5
check "with the policy another pod is blocked" "blocked" "$(direct)"
check "the gateway still reaches the backend" "200" "$(code "${BASE}/sso/public/get")"
kubectl -n "${NAMESPACE}" delete -f k8s/samples/network-policy-backend.yaml >/dev/null

echo "== modelo 9: app SSR servindo outros consumidores"
token="$(curl -s -d grant_type=client_credentials -d client_id=tools-cli --data-urlencode "client_secret=${CLI_SECRET}" \
    "${BASE}/auth/realms/tools/protocol/openid-connect/token" | jq -r .access_token)"
check "another system's token on a route of user groups -> 403" "403" \
    "$(code -H "Authorization: Bearer ${token}" "${BASE}/sso/group/get")"

echo
echo "passed=${pass} failed=${fail}"
[[ ${fail} -eq 0 ]]
