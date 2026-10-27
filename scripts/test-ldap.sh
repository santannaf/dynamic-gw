#!/usr/bin/env bash
# E2E do SSO com provider=ldap no k3d (pré-requisito: PROVIDER=ldap make sso-up).
# Login pelo formulário do próprio gateway (com CSRF), grupos do diretório,
# revalidação (remove alice do grupo no OpenLDAP), limite de tentativas,
# multi-réplica e trilha de auditoria.
set -uo pipefail

HOST_PORT="${HOST_PORT:-8080}"
BASE="http://localhost:${HOST_PORT}"
NAMESPACE="${NAMESPACE:-platform}"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

secret() { kubectl -n "${NAMESPACE}" get secret sso-secrets -o "jsonpath={.data.$1}" | base64 -d; }
PASSWORD="$(secret demo-password)"
LDAP_ADMIN="$(secret ldap-admin-password)"
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

# Visita a rota protegida (salva o destino), abre o formulário, envia com o
# token CSRF e segue até a rota. Imprime o status final.
form_login() {
    local user="$1" password="$2" jar="$3" target="$4" csrf
    curl -s -o /dev/null -c "${jar}" -b "${jar}" -H 'Accept: text/html' "${BASE}${target}"
    csrf="$(curl -s -c "${jar}" -b "${jar}" "${BASE}/_gateway/login" | grep -o 'name="_csrf" value="[^"]*"' \
        | sed -e 's/.*value="//' -e 's/"$//')"
    curl -s -L -c "${jar}" -b "${jar}" -o "${WORK}/body" -w '%{http_code}' \
        --data-urlencode "username=${user}" --data-urlencode "password=${password}" --data-urlencode "_csrf=${csrf}" \
        "${BASE}/_gateway/login"
}

header() { jq -r --arg h "$1" '.headers[$h][0] // empty' "${WORK}/body"; }

ldap_modify() {
    kubectl -n "${NAMESPACE}" exec -i deployment/openldap -- \
        ldapmodify -x -H ldap://localhost -D cn=admin,dc=tools,dc=local -w "${LDAP_ADMIN}" >/dev/null
}

echo "== scenario"
PROFILES="$(kubectl -n "${NAMESPACE}" get deployment dynamic-gateway \
    -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="SPRING_PROFILES_ACTIVE")].value}')"
check "gateway runs the ldap profile" "yes" "$([[ ",${PROFILES}," == *",ldap,"* ]] && echo yes || echo no)"
if [[ ",${PROFILES}," == *",s3,"* ]]; then
    # grep -c lê tudo: com pipefail, grep -q fecharia o pipe e o kubectl sairia com erro.
    loaded="$(kubectl -n "${NAMESPACE}" logs -l app.kubernetes.io/name=dynamic-gateway --tail=-1 --prefix=false \
        | grep -c 'Loaded route config snapshot from s3://')"
    check "routes come from the S3 snapshot" "yes" "$([[ "${loaded:-0}" -gt 0 ]] && echo yes || echo no)"
fi

echo "== anonymous"
check "browser navigation -> gateway login page" "302 ${BASE}/_gateway/login" \
    "$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' -H 'Accept: text/html' "${BASE}/sso/group/headers")"
check "API call without session -> 401" "401" "$(curl -s -o /dev/null -w '%{http_code}' "${BASE}/sso/auth/headers")"
check "Authorization header is not interpreted (public route keeps it)" "Bearer backend-token" \
    "$(curl -s -H 'Authorization: Bearer backend-token' "${BASE}/sso/public/headers" | jq -r '.headers.Authorization[0]')"

echo "== alice (tools-httpbin; also in ou=other's finance)"
check "form login lands on the protected route" "200" "$(form_login alice "${PASSWORD}" "${WORK}/alice" /sso/group/headers)"
check "backend sees the verified user" "alice" "$(header X-Auth-Request-User)"
check "only groups under ou=tools become authorities" "tools-httpbin" "$(header X-Auth-Request-Groups)"
check "whoami" "group:tools-httpbin" "$(curl -s -b "${WORK}/alice" "${BASE}/_gateway/whoami" | jq -r '.authorities | join(",")')"

echo "== bob, carol, bad passwords"
check "bob is authenticated but not in the group" "403" "$(form_login bob "${PASSWORD}" "${WORK}/bob" /sso/group/headers)"
check "carol (tools-admins) reads /internal/routes" "200" "$(form_login carol "${PASSWORD}" "${WORK}/carol" /internal/routes)"
check "wrong password -> back to the form with error" "302 ${BASE}/_gateway/login?error" \
    "$(curl -s -o /dev/null -c "${WORK}/x" -b "${WORK}/x" "${BASE}/_gateway/login" && \
       curl -s -o /dev/null -w '%{http_code} %{redirect_url}' -c "${WORK}/x" -b "${WORK}/x" \
         --data-urlencode username=alice --data-urlencode password=wrong \
         --data-urlencode "_csrf=$(curl -s -c "${WORK}/x" -b "${WORK}/x" "${BASE}/_gateway/login" | grep -o 'name="_csrf" value="[^"]*"' | sed -e 's/.*value="//' -e 's/"$//')" \
         "${BASE}/_gateway/login")"
# O limite é por réplica (sem estado compartilhado): com N réplicas, o 429
# chega em até N x max-failures tentativas. O lockout do AD vale por cima.
throttled=no
for i in $(seq 1 20); do
    if [[ "$(form_login mallory "wrong-${i}" "${WORK}/m" /sso/auth/get)" == "429" ]]; then
        throttled=yes
        break
    fi
done
check "repeated failures for one user end in 429" "yes" "${throttled}"
check "missing CSRF token -> 403" "403" \
    "$(curl -s -o /dev/null -w '%{http_code}' --data-urlencode username=alice --data-urlencode "password=${PASSWORD}" "${BASE}/_gateway/login")"

echo "== multiple replicas"
kubectl -n "${NAMESPACE}" scale deployment/dynamic-gateway --replicas=3 >/dev/null
kubectl -n "${NAMESPACE}" rollout status deployment/dynamic-gateway --timeout=180s >/dev/null
statuses=""
for _ in $(seq 1 9); do statuses+="$(curl -s -o /dev/null -w '%{http_code}' -b "${WORK}/alice" "${BASE}/sso/group/get") "; done
check "same cookie works on every replica" "200 200 200 200 200 200 200 200 200 " "${statuses}"

echo "== revalidation (alice leaves tools-httpbin in the directory)"
printf 'dn: cn=tools-httpbin,ou=tools,dc=tools,dc=local\nchangetype: modify\ndelete: member\nmember: uid=alice,ou=people,dc=tools,dc=local\n' | ldap_modify
sleep 35
statuses=""
for _ in $(seq 1 6); do statuses+="$(curl -s -o /dev/null -w '%{http_code}' -b "${WORK}/alice" "${BASE}/sso/group/get") "; done
check "after revalidate-after every replica denies" "403 403 403 403 403 403 " "${statuses}"
printf 'dn: cn=tools-httpbin,ou=tools,dc=tools,dc=local\nchangetype: modify\nadd: member\nmember: uid=alice,ou=people,dc=tools,dc=local\n' | ldap_modify
kubectl -n "${NAMESPACE}" scale deployment/dynamic-gateway --replicas=2 >/dev/null

echo "== logout"
check "logout returns to the login page" "302 ${BASE}/_gateway/login?logout" \
    "$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' -b "${WORK}/alice" -c "${WORK}/alice" "${BASE}/_gateway/logout")"
check "after logout the route asks for login again" "401" \
    "$(curl -s -o /dev/null -w '%{http_code}' -b "${WORK}/alice" "${BASE}/sso/group/get")"

echo "== audit trail"
kubectl -n "${NAMESPACE}" logs -l app.kubernetes.io/name=dynamic-gateway --since-time="${START}" --tail=-1 --prefix=false \
    | grep '^{"@timestamp"' > "${WORK}/audit.jsonl" || true
check "every audit line is JSON" "yes" "$(jq -e . "${WORK}/audit.jsonl" >/dev/null 2>&1 && echo yes || echo no)"
check "alice's login is audited" "yes" \
    "$(jq -e -s 'any(.[]; .user.name == "alice" and .event.action == "auth.login" and .event.outcome == "success")' "${WORK}/audit.jsonl" >/dev/null && echo yes || echo no)"
check "throttling is audited" "yes" \
    "$(jq -e -s 'any(.[]; .event.reason == "throttled" and .user.name == "mallory")' "${WORK}/audit.jsonl" >/dev/null && echo yes || echo no)"
check "the password never reaches the audit" "0" "$(grep -c -e "${PASSWORD}" -e 'wrong-' -e 'GW_SESSION=' "${WORK}/audit.jsonl")"

echo
echo "passed=${pass} failed=${fail}"
[[ ${fail} -eq 0 ]]
