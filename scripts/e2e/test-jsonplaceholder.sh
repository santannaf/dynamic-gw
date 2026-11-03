#!/usr/bin/env bash
# E2E das políticas de rota contra uma API de verdade, o JSONPlaceholder
# (https://jsonplaceholder.typicode.com): proxy, rate limit, throttling, filtros,
# mapping e agregação. Só curl + jq, e saída para a internet.
#
#   v2 (make lab): aplica k8s/samples/routes-jsonplaceholder.yaml e deixa as rotas no
#                  cluster, para testar na mão depois (make jsonplaceholder-down tira)
#   v1 (profile standalone ou standalone-sso): as mesmas rotas já vêm na imagem
#   LOCAL=1:       gateway fora do cluster, na porta HOST_PORT, com o profile standalone
#                  (./gradlew :gateway:bootRun --args='--spring.profiles.active=standalone')
#
# No cluster, os limites contam por réplica: o teste reduz o gateway a uma réplica e
# devolve o número original no fim.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT}"
HOST_PORT="${HOST_PORT:-8080}"
BASE="http://localhost:${HOST_PORT}"
NAMESPACE="${NAMESPACE:-platform}"
WORK="$(mktemp -d)"
START="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
# Chave própria: o orçamento começa cheio mesmo depois de testes na mão na mesma rota.
API_KEY="e2e-$(date +%s)-${RANDOM}"

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

# Login de um usuário do diretório, como um browser: formulário do Keycloak (provider
# keycloak) ou o do próprio gateway (provider ldap). Imprime o status final da rota.
login() {
    local user="$1" jar="$2" target="$3" page action csrf
    if [[ ",${PROFILES}," == *",ldap,"* ]]; then
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
    if [[ -z "${action}" ]]; then
        echo "000"
        return
    fi
    curl -s -L -c "${jar}" -b "${jar}" -H 'Accept: text/html' -o "${WORK}/body" -w '%{http_code}' \
        --data-urlencode "username=${user}" --data-urlencode "password=${PASSWORD}" "${action}"
}

has_profile() { [[ ",${PROFILES}," == *",$1,"* ]]; }

if [[ "${LOCAL:-}" == "1" ]]; then
    PROFILES=""
    trap 'rm -rf "${WORK}"' EXIT
    echo "== scenario (gateway outside the cluster, at ${BASE})"
    check "the routes answer" "0" "$(wait_route /jp/posts/1; echo $?)"
else
    PROFILES="$(kubectl -n "${NAMESPACE}" get deployment dynamic-gateway \
        -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="SPRING_PROFILES_ACTIVE")].value}')"
    REPLICAS="$(kubectl -n "${NAMESPACE}" get deployment dynamic-gateway -o jsonpath='{.spec.replicas}')"
    cleanup() {
        kubectl -n "${NAMESPACE}" scale deployment/dynamic-gateway --replicas="${REPLICAS}" >/dev/null
        rm -rf "${WORK}"
    }
    trap cleanup EXIT
    echo "== scenario (profiles: ${PROFILES:-none})"
    if [[ ",${PROFILES}," == *",standalone,"* || ",${PROFILES}," == *",standalone-sso,"* ]]; then
        echo "  v1: routes from the image"
    elif kubectl get crd gatewayroutes.platform.saca.pags >/dev/null 2>&1; then
        kubectl apply -f k8s/samples/routes-jsonplaceholder.yaml >/dev/null
        echo "  v2: GatewayRoutes applied; they stay in the cluster"
    else
        echo "No GatewayRoute CRD and no v1 profile: run make lab, or LOCAL=1 for a gateway outside the cluster" >&2
        exit 2
    fi
    kubectl -n "${NAMESPACE}" scale deployment/dynamic-gateway --replicas=1 >/dev/null
    kubectl -n "${NAMESPACE}" rollout status deployment/dynamic-gateway --timeout=180s >/dev/null
    check "the routes are served by one replica" "0" "$(wait_route /jp/posts/1; echo $?)"
fi

echo "== proxy (/jp/posts)"
status="$(body "${BASE}/jp/posts/1")"
check "post 1 answers" "200" "${status}"
check "it is the real post 1" "1 1" "$(jq -r '"\(.id) \(.userId)"' "${WORK}/body")"
check "the list has 100 posts" "100" "$(curl -s "${BASE}/jp/posts" | jq length)"

echo "== rate limit (3 per 10s for each X-Api-Key)"
statuses=""
for _ in 1 2 3 4; do statuses+="$(body -H "X-Api-Key: ${API_KEY}" "${BASE}/jp/limitado/posts/1") "; done
check "three pass, the fourth is refused" "200 200 200 429 " "${statuses}"
check "Retry-After on the refusal" "yes" "$([[ "$(header Retry-After)" =~ ^[0-9]+$ ]] && echo yes || echo no)"
check "the refusal says why, in the gateway's error JSON" "429 Too Many Requests /jp/limitado/posts/1" \
    "$(jq -r '"\(.status) \(.error) \(.path)"' "${WORK}/body")"
check "another key has its own budget" "200" "$(code -H "X-Api-Key: ${API_KEY}-b" "${BASE}/jp/limitado/posts/1")"

echo "== throttle (1 at a time, 1 in the queue)"
for i in 1 2 3 4 5 6; do
    curl -s -o "${WORK}/throttle-body-${i}" -w '%{http_code}\n' "${BASE}/jp/lento/comments" > "${WORK}/throttle-${i}" &
done
wait
answered="$(cat "${WORK}"/throttle-[0-9] | grep -c '^200$' || true)"
refused="$(cat "${WORK}"/throttle-[0-9] | grep -c '^503$' || true)"
check "six at once: some run, the excess gets 503" "yes yes 6" \
    "$([[ ${answered} -ge 1 ]] && echo yes || echo no) $([[ ${refused} -ge 1 ]] && echo yes || echo no) $((answered + refused))"
check "the 503 says why, in the gateway's error JSON" "503 Service Unavailable" \
    "$(cat "${WORK}"/throttle-body-* | jq -r 'select(.status? == 503) | "\(.status) \(.error)"' 2>/dev/null | head -1)"

echo "== filters and mapping (/jp/artigos, a facade in Portuguese)"
status="$(body "${BASE}/jp/artigos/1")"
check "article 1 answers" "200" "${status}"
check "fields renamed" "autorId,conteudo,id,titulo" "$(jq -r 'keys | join(",")' "${WORK}/body")"
check "response header added" "artigos" "$(header X-Fachada)"
check "X-Powered-By of the API hidden" "" "$(header X-Powered-By)"
check "the list is renamed too" "100 true" "$(curl -s "${BASE}/jp/artigos" | jq -r '"\(length) \(.[0].titulo != null)"')"
status="$(body -X POST -H 'Content-Type: application/json' -d '{"title":"Olá","body":"teste","userId":7}' "${BASE}/jp/artigos")"
check "POST goes through (the API pretends to create)" "201" "${status}"
check "and its answer comes renamed" "Olá 7 101" "$(jq -r '"\(.titulo) \(.autorId) \(.id)"' "${WORK}/body")"
head -c 2048 /dev/zero | tr '\0' a > "${WORK}/big"
check "body over 1KB refused" "413" \
    "$(code -X POST -H 'Content-Type: application/json' --data-binary @"${WORK}/big" "${BASE}/jp/artigos")"

echo "== fixed query and allow (/jp/meus-posts)"
status="$(body "${BASE}/jp/meus-posts")"
check "answers" "200" "${status}"
check "only the 10 posts of user 1" "10" "$(jq length "${WORK}/body")"
check "only id and title" "id,title" "$(jq -r '[.[] | keys | join(",")] | unique | join(";")' "${WORK}/body")"

echo "== aggregate"
status="$(body "${BASE}/jp/post-completo/1")"
check "post and comments, one answer" "200" "${status}"
check "complete" "true" "$(header X-Gateway-Completed)"
check "the post, reduced" "1 body,id,title" "$(jq -r '"\(.post.id) \(.post | keys | join(","))"' "${WORK}/body")"
check "its 5 comments, reduced" "5 email,name" \
    "$(jq -r '"\(.comentarios | length) \(.comentarios[0] | keys | join(","))"' "${WORK}/body")"
status="$(body "${BASE}/jp/post-parcial/1")"
check "a missing backend still answers" "200" "${status}"
check "partial answer is signalled" "false" "$(header X-Gateway-Completed)"
check "only the backend that answered" "post" "$(jq -r 'keys | join(",")' "${WORK}/body")"

if has_profile keycloak || has_profile ldap; then
    echo "== private routes: users of the lab's directory"
    PASSWORD="$(kubectl -n "${NAMESPACE}" get secret sso-secrets -o jsonpath='{.data.demo-password}' | base64 -d)"
    check "anonymous call refused" "401" "$(code "${BASE}/jp/privado/posts/1")"
    check "alice logs in and reads /jp/privado" "200" "$(login alice "${WORK}/alice" /jp/privado/posts/1)"
    statuses=""
    for _ in 1 2 3 4 5; do statuses+="$(code -b "${WORK}/alice" "${BASE}/jp/privado/posts/1") "; done
    check "alice spends her own budget (5 per 10s): 4 more, then 429" "200 200 200 200 429 " "${statuses}"
    check "bob (no group) reads /jp/privado, with a budget of his own" "200" "$(login bob "${WORK}/bob" /jp/privado/posts/1)"
    status="$(body -b "${WORK}/alice" "${BASE}/jp/usuarios/1")"
    check "alice (tools-httpbin) reads users without address, phone and company" "200 email,id,name,username,website" \
        "${status} $(jq -r 'keys | join(",")' "${WORK}/body")"
    check "bob cannot read users" "403" "$(code -b "${WORK}/bob" "${BASE}/jp/usuarios/1")"
    check "alice reads in /jp/gestao" "200" "$(code -b "${WORK}/alice" "${BASE}/jp/gestao/posts/1")"
    check "alice cannot write in /jp/gestao" "403" \
        "$(code -b "${WORK}/alice" -X POST -H 'Content-Type: application/json' -d '{"title":"x"}' "${BASE}/jp/gestao/posts")"
    check "carol (tools-admins) logs in" "200" "$(login carol "${WORK}/carol" /jp/gestao/posts/1)"
    check "carol writes (the API pretends to save)" "201" \
        "$(code -b "${WORK}/carol" -X POST -H 'Content-Type: application/json' -d '{"title":"x"}' "${BASE}/jp/gestao/posts")"
fi

if has_profile keycloak || has_profile oauth; then
    echo "== private routes: a service account with a bearer token"
    CLI_SECRET="$(kubectl -n "${NAMESPACE}" get secret sso-secrets -o jsonpath='{.data.tools-cli-client-secret}' | base64 -d)"
    TOKEN="$(curl -s -d grant_type=client_credentials -d client_id=tools-cli --data-urlencode "client_secret=${CLI_SECRET}" \
        "${BASE}/auth/realms/tools/protocol/openid-connect/token" | jq -r .access_token)"
    check "tools-cli reads /jp/privado (any identity)" "200" \
        "$(code -H "Authorization: Bearer ${TOKEN}" "${BASE}/jp/privado/posts/1")"
    check "tools-cli has no group for /jp/usuarios" "403" \
        "$(code -H "Authorization: Bearer ${TOKEN}" "${BASE}/jp/usuarios/1")"
fi

if has_profile keycloak || has_profile ldap || has_profile oauth; then
    echo "== audit trail"
    kubectl -n "${NAMESPACE}" logs -l app.kubernetes.io/name=dynamic-gateway --since-time="${START}" --tail=-1 --prefix=false \
        | grep '^{"@timestamp"' > "${WORK}/audit.jsonl" || true
    check "429 audited as rate_limited" "yes" \
        "$(jq -e -s 'any(.[]; .event.reason == "rate_limited" and .gateway.route.id == "jp-limitado")' "${WORK}/audit.jsonl" >/dev/null && echo yes || echo no)"
    check "503 audited as throttled" "yes" \
        "$(jq -e -s 'any(.[]; .event.reason == "throttled" and .gateway.route.id == "jp-lento")' "${WORK}/audit.jsonl" >/dev/null && echo yes || echo no)"
    check "401 audited as unauthenticated" "yes" \
        "$(jq -e -s 'any(.[]; .event.reason == "unauthenticated" and .gateway.route.id == "jp-privado")' "${WORK}/audit.jsonl" >/dev/null && echo yes || echo no)"
    check "403 audited as forbidden" "yes" \
        "$(jq -e -s 'any(.[]; .event.reason == "forbidden" and .gateway.route.id == "jp-usuarios")' "${WORK}/audit.jsonl" >/dev/null && echo yes || echo no)"
fi

echo
echo "passed=${pass} failed=${fail}"
[[ ${fail} -eq 0 ]]
