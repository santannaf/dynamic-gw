#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

BASE_URL="${BASE_URL:-http://localhost:8080}"
NAMESPACE="${NAMESPACE:-platform}"
SETTLE_SECS="${SETTLE_SECS:-3}"

step() { echo; echo "==> $*"; }

step "1. Apply httpbin route"
kubectl apply -f k8s/samples/route-httpbin.yaml
sleep "${SETTLE_SECS}"

step "2. Verify route works (retry — httpbin.org occasionally flakes)"
curl -fsS --retry 5 --retry-delay 2 --retry-all-errors \
    -o /dev/null -w "HTTP %{http_code}\n" "${BASE_URL}/httpbin/get"

step "3. Restart gateway deployment"
kubectl rollout restart deployment/dynamic-gateway -n "${NAMESPACE}"
kubectl rollout status  deployment/dynamic-gateway -n "${NAMESPACE}" --timeout=180s

step "4. After restart: /httpbin/get must still work (retry — upstream flake-tolerant)"
status=""
for attempt in 1 2 3 4 5 6; do
    status=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}/httpbin/get")
    echo "  attempt ${attempt}: HTTP ${status}"
    [ "${status}" = "200" ] && break
    sleep 2
done
[ "${status}" = "200" ] || { echo "FAIL: gateway did not recover the route from snapshot"; exit 1; }

step "5. After restart: /internal/routes must list httpbin-route"
curl -fsS "${BASE_URL}/internal/routes" | python3 -m json.tool

echo "PASS: gateway recovered all published routes from the snapshot."
