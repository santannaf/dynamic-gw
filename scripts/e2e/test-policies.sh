#!/usr/bin/env bash
# E2E das políticas de rota (ETT-988) no k3d: filtros, transformação,
# agregação, rate limit e throttling. Só curl + jq.
#
#   v2 (make deploy, com ou sem SSO): aplica k8s/samples/routes-policies.yaml e remove no fim
#   v1 (make standalone-up): as mesmas rotas já vêm no application-standalone.yaml da imagem
#
# Os limites contam por réplica: o teste reduz o gateway a uma réplica e
# devolve o número original no fim.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT}"
HOST_PORT="${HOST_PORT:-8080}"
BASE="http://localhost:${HOST_PORT}"
NAMESPACE="${NAMESPACE:-platform}"
WORK="$(mktemp -d)"
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
body() { curl -s -D "${WORK}/headers" -o "${WORK}/body" -w '%{http_code}' "$@"; }
header() { grep -i "^$1:" "${WORK}/headers" | head -1 | cut -d' ' -f2- | tr -d '\r'; }

# Espera 10 respostas seguidas abaixo de 500 em $1 (DNS do nginx-lb e propagação das rotas).
wait_route() {
    local ok=0 status=""
    for _ in $(seq 1 120); do
        status="$(code "${BASE}$1")"
        if [[ "${status}" =~ ^[1-4][0-9][0-9]$ && "${status}" != "404" ]]; then ok=$((ok + 1)); else ok=0; fi
        [[ "${ok}" -ge 10 ]] && return 0
        sleep 1
    done
    echo "  route $1 still answers ${status}" >&2
    return 1
}

PROFILES="$(kubectl -n "${NAMESPACE}" get deployment dynamic-gateway \
    -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="SPRING_PROFILES_ACTIVE")].value}')"
REPLICAS="$(kubectl -n "${NAMESPACE}" get deployment dynamic-gateway -o jsonpath='{.spec.replicas}')"
cleanup() {
    kubectl -n "${NAMESPACE}" scale deployment/dynamic-gateway --replicas="${REPLICAS}" >/dev/null
    if [[ ",${PROFILES}," != *",standalone,"* ]]; then
        kubectl delete -f k8s/samples/routes-policies.yaml --ignore-not-found >/dev/null
    fi
    rm -rf "${WORK}"
}
trap cleanup EXIT

echo "== scenario (profiles: ${PROFILES:-none})"
if [[ ",${PROFILES}," == *",standalone,"* ]]; then
    echo "  v1: routes from the image"
else
    kubectl apply -f k8s/samples/routes-policies.yaml >/dev/null
    echo "  v2: GatewayRoutes applied"
fi
kubectl -n "${NAMESPACE}" scale deployment/dynamic-gateway --replicas=1 >/dev/null
kubectl -n "${NAMESPACE}" rollout status deployment/dynamic-gateway --timeout=180s >/dev/null
check "policy routes are served by one replica" "0" "$(wait_route /pol/filters/get; echo $?)"

echo "== filters"
status="$(body -H 'X-Debug: 1' "${BASE}/pol/filters/headers")"
check "route answers" "200" "${status}"
check "request header added" "gateway" "$(jq -r '.headers["X-Source"][0] // empty' "${WORK}/body")"
check "request header removed" "" "$(jq -r '.headers["X-Debug"][0] // empty' "${WORK}/body")"
check "query parameter added" "borda" "$(jq -r '.args.origem[0] // empty' "${WORK}/body")"
check "path prefixed" "yes" "$(jq -r '.url' "${WORK}/body" | grep -q '/anything/headers' && echo yes || echo no)"
check "response header added" "filters" "$(header X-Policy)"
status="$(body -H 'X-Canal: cliente' "${BASE}/pol/rewrite/pedidos?debug=1&pagina=2")"
check "rewritten route answers" "200" "${status}"
check "path rewritten" "yes" "$(jq -r '.url' "${WORK}/body" | grep -q '/anything/reescrito/pedidos' && echo yes || echo no)"
check "request header replaced" "borda" "$(jq -r '(.headers["X-Canal"] // []) | join(",")' "${WORK}/body")"
check "query parameter removed, the other kept" "-2" "$(jq -r '"\(.args.debug[0] // "")-\(.args.pagina[0] // "")"' "${WORK}/body")"
check "response header replaced" "rewrite" "$(header X-Policy)"
status="$(body "${BASE}/pol/setpath/qualquer?X-Interno=segredo&X-Publico=ok")"
check "fixed path answers" "200" "${status}"
check "response header removed, the other kept" "-ok" "$(header X-Interno)-$(header X-Publico)"
head -c 2048 /dev/zero | tr '\0' a > "${WORK}/big"
check "body over 1KB refused" "413" "$(code -X POST --data-binary @"${WORK}/big" "${BASE}/pol/setpath/qualquer")"

echo "== transform"
status="$(body "${BASE}/pol/transform/pedido")"
check "route answers" "200" "${status}"
check "only method and url, url renamed, under eco" "endereco,method" "$(jq -r '.eco | keys | join(",")' "${WORK}/body")"
check "Content-Length matches the new body" "$(wc -c < "${WORK}/body" | tr -d ' ')" "$(header Content-Length)"

echo "== aggregate"
status="$(body "${BASE}/pol/painel/42")"
check "two backends, one answer" "200" "${status}"
check "complete" "true" "$(header X-Gateway-Completed)"
check "pedido under its group, path variable used" "yes" \
    "$(jq -r '.pedido.url' "${WORK}/body" | grep -q '/anything/pedidos/42$' && echo yes || echo no)"
check "cliente under its group, transformed" "GET" "$(jq -r '.cliente.method' "${WORK}/body")"
status="$(body "${BASE}/pol/parcial/7")"
check "a failing backend still answers" "200" "${status}"
check "partial answer is signalled" "false" "$(header X-Gateway-Completed)"
check "only the backend that answered" "pedido" "$(jq -r 'keys | join(",")' "${WORK}/body")"
check "aggregate answers GET only" "404" "$(code -X POST "${BASE}/pol/painel/42")"

echo "== rate limit (5 per 10s for everyone)"
statuses=""
for _ in $(seq 1 6); do statuses+="$(body "${BASE}/pol/limited/get") "; done
check "five pass, the sixth is refused" "200 200 200 200 200 429 " "${statuses}"
check "Retry-After on the refusal" "yes" "$([[ "$(header Retry-After)" =~ ^[0-9]+$ ]] && echo yes || echo no)"
check "budget is empty" "0" "$(header RateLimit-Remaining)"

echo "== rate limit per caller (2 per 10s for each X-Api-Key)"
statuses=""
for key in a a a b; do statuses+="$(code -H "X-Api-Key: ${key}" "${BASE}/pol/per-client/get") "; done
check "each key has its own budget" "200 200 429 200 " "${statuses}"

echo "== throttle (1 at a time, 1 in the queue)"
for i in 1 2 3; do
    curl -s -o /dev/null -w '%{http_code}\n' "${BASE}/pol/throttled/delay/2" > "${WORK}/throttle-${i}" &
    sleep 0.2
done
wait
check "one runs, one waits, one is refused" "200 200 503" "$(cat "${WORK}"/throttle-* | sort | tr '\n' ' ' | sed 's/ $//')"

if [[ ",${PROFILES}," == *",keycloak,"* || ",${PROFILES}," == *",ldap,"* || ",${PROFILES}," == *",oauth,"* ]]; then
    echo "== audit trail"
    kubectl -n "${NAMESPACE}" logs -l app.kubernetes.io/name=dynamic-gateway --since-time="${START}" --tail=-1 --prefix=false \
        | grep '^{"@timestamp"' > "${WORK}/audit.jsonl" || true
    check "429 audited as rate_limited" "yes" \
        "$(jq -e -s 'any(.[]; .event.reason == "rate_limited" and .gateway.route.id == "policy-limited")' "${WORK}/audit.jsonl" >/dev/null && echo yes || echo no)"
    check "503 audited as throttled" "yes" \
        "$(jq -e -s 'any(.[]; .event.reason == "throttled" and .gateway.route.id == "policy-throttled")' "${WORK}/audit.jsonl" >/dev/null && echo yes || echo no)"
fi

echo
echo "passed=${pass} failed=${fail}"
[[ ${fail} -eq 0 ]]
