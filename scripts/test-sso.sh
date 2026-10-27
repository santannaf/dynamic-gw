#!/usr/bin/env bash
# E2E do SSO com provider=keycloak no k3d (pré-requisito: PROVIDER=keycloak make sso-up).
# Usa só curl + jq: login de browser roteirizado (formulário do Keycloak),
# bearer de conta de serviço, multi-réplica, logout e trilha de auditoria.
set -uo pipefail

HOST_PORT="${HOST_PORT:-8080}"
BASE="http://localhost:${HOST_PORT}"
NAMESPACE="${NAMESPACE:-platform}"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

secret() { kubectl -n "${NAMESPACE}" get secret sso-secrets -o "jsonpath={.data.$1}" | base64 -d; }
PASSWORD="$(secret demo-password)"
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

# Browser login: segue até o formulário do Keycloak, envia usuário/senha e
# volta pelo callback do gateway até a URL protegida. Imprime o status final.
browser_login() {
    local user="$1" jar="$2" target="$3" page action
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

header() { jq -r --arg h "$1" '.headers[$h][0] // empty' "${WORK}/body"; }

echo "== scenario"
PROFILES="$(kubectl -n "${NAMESPACE}" get deployment dynamic-gateway \
    -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="SPRING_PROFILES_ACTIVE")].value}')"
check "gateway runs the keycloak profile" "yes" "$([[ ",${PROFILES}," == *",keycloak,"* ]] && echo yes || echo no)"
if [[ ",${PROFILES}," == *",s3,"* ]]; then
    # grep -c lê tudo: com pipefail, grep -q fecharia o pipe e o kubectl sairia com erro.
    loaded="$(kubectl -n "${NAMESPACE}" logs -l app.kubernetes.io/name=dynamic-gateway --tail=-1 --prefix=false \
        | grep -c 'Loaded route config snapshot from s3://')"
    check "routes come from the S3 snapshot" "yes" "$([[ "${loaded:-0}" -gt 0 ]] && echo yes || echo no)"
fi

echo "== anonymous"
check "API call without credentials -> 401" "401" \
    "$(curl -s -o /dev/null -w '%{http_code}' "${BASE}/sso/auth/headers")"
check "browser navigation -> login" "302 ${BASE}/_gateway/oauth2/authorization/keycloak" \
    "$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' -H 'Accept: text/html' "${BASE}/sso/group/headers")"
authorize="$(curl -s -o /dev/null -w '%{redirect_url}' -c "${WORK}/anon" "${BASE}/_gateway/oauth2/authorization/keycloak")"
check "login start uses PKCE S256" "S256" "$(printf '%s' "${authorize}" | grep -o 'code_challenge_method=[A-Z0-9]*' | cut -d= -f2)"
check "public route needs nothing" "200" "$(curl -s -o /dev/null -w '%{http_code}' "${BASE}/sso/public/get")"

echo "== alice (tools-httpbin)"
check "login lands on the protected route" "200" "$(browser_login alice "${WORK}/alice" /sso/group/headers)"
curl -s -b "${WORK}/alice" -H 'X-Auth-Request-User: mallory' -H 'Cookie: theme=dark' \
    "${BASE}/sso/group/headers" -o "${WORK}/body"
check "backend sees the verified user" "alice" "$(header X-Auth-Request-User)"
check "backend sees the group" "tools-httpbin" "$(header X-Auth-Request-Groups)"
check "session cookie is not forwarded" "" "$(header Cookie | grep -o 'GW_SESSION' || true)"
check "read side of /sso/rw" "200" "$(curl -s -o /dev/null -w '%{http_code}' -b "${WORK}/alice" "${BASE}/sso/rw/get")"
check "write side of /sso/rw needs tools-admins" "403" \
    "$(curl -s -o /dev/null -w '%{http_code}' -b "${WORK}/alice" -X POST "${BASE}/sso/rw/post")"
check "whoami" "alice" "$(curl -s -b "${WORK}/alice" "${BASE}/_gateway/whoami" | jq -r .user)"

echo "== bob (no groups) and carol (tools-admins)"
check "bob logs in" "403" "$(browser_login bob "${WORK}/bob" /sso/group/headers)"
check "carol logs in" "200" "$(browser_login carol "${WORK}/carol" /sso/admin/headers)"
check "carol reads /internal/routes" "200" "$(curl -s -o /dev/null -w '%{http_code}' -b "${WORK}/carol" "${BASE}/internal/routes")"
check "alice cannot read /internal/routes" "403" "$(curl -s -o /dev/null -w '%{http_code}' -b "${WORK}/alice" "${BASE}/internal/routes")"

echo "== service account (tools-cli, client credentials)"
token="$(curl -s -d grant_type=client_credentials -d client_id=tools-cli --data-urlencode "client_secret=${CLI_SECRET}" \
    "${BASE}/auth/realms/tools/protocol/openid-connect/token" | jq -r .access_token)"
curl -s -H "Authorization: Bearer ${token}" "${BASE}/sso/auth/headers" -o "${WORK}/body" -w '%{http_code}' > "${WORK}/status"
check "bearer reaches an authenticated route" "200" "$(cat "${WORK}/status")"
check "backend sees the client id" "tools-cli" "$(header X-Auth-Request-Client-Id)"
check "token is not forwarded by default" "" "$(header Authorization)"
curl -s -H "Authorization: Bearer ${token}" "${BASE}/sso/token/headers" -o "${WORK}/body" >/dev/null
check "forwardToken needs the group (service account has none)" "" "$(header Authorization)"
check "service account with gateway-admin reads /internal/routes" "200" \
    "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer ${token}" "${BASE}/internal/routes")"
foreign="$(curl -s -d grant_type=password -d client_id=admin-cli -d username=admin --data-urlencode "password=${ADMIN_PASSWORD}" \
    "${BASE}/auth/realms/master/protocol/openid-connect/token" | jq -r .access_token)"
check "token from another realm/audience -> 401" "401" \
    "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer ${foreign}" "${BASE}/sso/auth/headers")"

echo "== multiple replicas"
kubectl -n "${NAMESPACE}" scale deployment/dynamic-gateway --replicas=3 >/dev/null
kubectl -n "${NAMESPACE}" rollout status deployment/dynamic-gateway --timeout=180s >/dev/null
statuses=""
pods=""
for _ in $(seq 1 12); do
    out="$(curl -s -o /dev/null -D - -w '%{http_code}' -b "${WORK}/alice" "${BASE}/sso/group/get")"
    statuses+="$(printf '%s' "${out}" | tail -1) "
    pods+="$(printf '%s' "${out}" | grep -i '^x-upstream:' | tr -d '\r' | cut -d' ' -f2)"$'\n'
done
check "same cookie works on every replica" "200 200 200 200 200 200 200 200 200 200 200 200 " "${statuses}"
check "requests spread over more than one pod" "yes" "$( [[ $(printf '%s' "${pods}" | sort -u | grep -c .) -gt 1 ]] && echo yes || echo no)"
kubectl -n "${NAMESPACE}" scale deployment/dynamic-gateway --replicas=2 >/dev/null

echo "== edge"
check "external reload is blocked by nginx-lb" "403" \
    "$(curl -s -o /dev/null -w '%{http_code}' -X POST "${BASE}/internal/routes/reload")"

echo "== logout"
logout="$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' -b "${WORK}/alice" -c "${WORK}/alice" "${BASE}/_gateway/logout")"
check "logout goes to Keycloak end-session" "302" "${logout%% *}"
curl -s -L -o /dev/null -b "${WORK}/alice" -c "${WORK}/alice" "${logout#* }"
page="$(curl -s -L -b "${WORK}/alice" -c "${WORK}/alice" -H 'Accept: text/html' "${BASE}/sso/group/headers")"
check "after logout the login form is back" "yes" "$(printf '%s' "${page}" | grep -q 'login-actions/authenticate' && echo yes || echo no)"

echo "== audit trail"
kubectl -n "${NAMESPACE}" logs -l app.kubernetes.io/name=dynamic-gateway --since-time="${START}" --tail=-1 --prefix=false \
    | grep '^{"@timestamp"' > "${WORK}/audit.jsonl" || true
check "every audit line is JSON" "yes" "$(jq -e . "${WORK}/audit.jsonl" >/dev/null 2>&1 && echo yes || echo no)"
check "alice's access is audited" "yes" \
    "$(jq -e -s 'any(.[]; .user.name == "alice" and .event.action == "route.access" and .event.outcome == "success")' "${WORK}/audit.jsonl" >/dev/null && echo yes || echo no)"
check "bob's denial is audited" "yes" \
    "$(jq -e -s 'any(.[]; .user.name == "bob" and .event.reason == "forbidden")' "${WORK}/audit.jsonl" >/dev/null && echo yes || echo no)"
check "no token or password in the audit" "0" \
    "$(grep -c -e 'eyJ' -e "${PASSWORD}" -e 'GW_SESSION=' "${WORK}/audit.jsonl")"
check "Keycloak logged the logins" "yes" \
    "$(kubectl -n "${NAMESPACE}" logs deployment/keycloak --since-time="${START}" | grep -q 'type="LOGIN"' && echo yes || echo no)"

echo
echo "passed=${pass} failed=${fail}"
[[ ${fail} -eq 0 ]]
