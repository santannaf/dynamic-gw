#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

BASE_URL="${BASE_URL:-http://localhost:8080}"
NAMESPACE="${NAMESPACE:-platform}"
SETTLE_SECS="${SETTLE_SECS:-3}"

step() { echo; echo "==> $*"; }

step "1. Gateway health"
curl -fsS "${BASE_URL}/actuator/health" | python3 -m json.tool

step "2. Initial /internal/routes (expect empty or pre-existing routes)"
curl -fsS "${BASE_URL}/internal/routes" | python3 -m json.tool

step "3. Apply httpbin route"
kubectl apply -f k8s/samples/route-httpbin.yaml
sleep "${SETTLE_SECS}"

step "4. /internal/routes after apply"
curl -fsS "${BASE_URL}/internal/routes" | python3 -m json.tool

step "5. Hit gateway -> httpbin (with retry; httpbin.org is occasionally flaky)"
curl -fsS --retry 5 --retry-delay 2 --retry-all-errors \
    -o /tmp/httpbin.json -w "HTTP %{http_code}\n" "${BASE_URL}/httpbin/get"
head -c 200 /tmp/httpbin.json; echo

step "6. Patch route (stripPrefix 1 -> 2)"
kubectl patch gatewayroute httpbin-route -n "${NAMESPACE}" \
    --type=merge -p '{"spec":{"stripPrefix":2}}'
sleep "${SETTLE_SECS}"
curl -fsS "${BASE_URL}/internal/routes" | python3 -m json.tool

step "7. Restore stripPrefix to 1"
kubectl patch gatewayroute httpbin-route -n "${NAMESPACE}" \
    --type=merge -p '{"spec":{"stripPrefix":1}}'
sleep "${SETTLE_SECS}"

step "8. Delete route"
kubectl delete -f k8s/samples/route-httpbin.yaml
sleep "${SETTLE_SECS}"

step "9. /internal/routes after delete (expect no httpbin-route)"
curl -fsS "${BASE_URL}/internal/routes" | python3 -m json.tool

step "10. Gateway should now 404 on /httpbin/get"
status=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}/httpbin/get") || true
echo "HTTP ${status} (expected 404)"
[ "${status}" = "404" ] && echo "PASS" || { echo "FAIL"; exit 1; }
