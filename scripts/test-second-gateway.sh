#!/usr/bin/env bash
# E2E de dois gateways isolados no mesmo cluster (pré-requisito: make deploy e
# make second-gateway-up). Confere o snapshot de cada namespace direto na
# ConfigMap, o que funciona com ou sem SSO no gateway da plataforma.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

HOST_PORT="${HOST_PORT:-8080}"
PLATFORM="${PLATFORM:-platform}"
SECOND="${SECOND:-borda-exemplo}"
HOST="${SECOND}.localhost"
WORK="$(mktemp -d)"

cleanup() {
    kubectl -n "${PLATFORM}" delete gatewayroute iso-platform iso-platform-late --ignore-not-found >/dev/null
    kubectl -n "${SECOND}" delete gatewayroute iso-second --ignore-not-found >/dev/null
    kubectl -n "${SECOND}" scale deployment/gateway-route-operator --replicas=1 >/dev/null
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

route() {
    cat <<YAML | kubectl apply -f - >/dev/null
apiVersion: platform.saca.pags/v1alpha1
kind: GatewayRoute
metadata:
  name: $2
  namespace: $1
spec:
  path: /iso/$2/**
  targetUri: http://go-httpbin.platform.svc.cluster.local:8080
  stripPrefix: 2
  team: platform-team
  access:
    mode: public
YAML
}

snapshot() {
    local encoded
    encoded="$(kubectl -n "$1" get configmap gateway-routes -o 'jsonpath={.binaryData.routes\.yaml\.gz}')"
    if [[ -n "${encoded}" ]]; then
        printf '%s' "${encoded}" | base64 -d | gunzip
    else
        kubectl -n "$1" get configmap gateway-routes -o 'jsonpath={.data.routes\.yaml}'
    fi
}

has_route() {
    local _i
    for _i in $(seq 1 30); do
        if [[ "$(snapshot "$1" | grep -c "/iso/$2/")" -gt 0 ]]; then
            echo yes
            return
        fi
        sleep 1
    done
    echo no
}

lacks_route() {
    [[ "$(snapshot "$1" | grep -c "/iso/$2/")" -eq 0 ]] && echo yes || echo no
}

second_code() {
    curl -s -o /dev/null -w '%{http_code}' -H "Host: ${HOST}" "http://localhost:${HOST_PORT}$1"
}

wait_second_code() {
    local _i code=""
    for _i in $(seq 1 30); do
        code="$(second_code "$1")"
        [[ "${code}" == "$2" ]] && break
        sleep 1
    done
    echo "${code}"
}

echo "== routes stay in their namespace"
route "${PLATFORM}" iso-platform
route "${SECOND}" iso-second
check "platform publishes its route" "yes" "$(has_route "${PLATFORM}" iso-platform)"
check "second gateway publishes its route" "yes" "$(has_route "${SECOND}" iso-second)"
check "platform snapshot has no route of the second gateway" "yes" "$(lacks_route "${PLATFORM}" iso-second)"
check "second snapshot has no route of the platform" "yes" "$(lacks_route "${SECOND}" iso-platform)"
check "second gateway serves its route" "200" "$(wait_second_code /iso/iso-second/get 200)"
check "second gateway does not serve the platform's route" "404" "$(second_code /iso/iso-platform/get)"
check "the reload stays unreachable from outside" "no" \
    "$([[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Host: ${HOST}" "http://localhost:${HOST_PORT}/internal/routes/reload")" == "200" ]] && echo yes || echo no)"

echo "== operators are independent"
kubectl -n "${SECOND}" scale deployment/gateway-route-operator --replicas=0 >/dev/null
kubectl -n "${SECOND}" wait --for=delete pod -l app.kubernetes.io/name=gateway-route-operator --timeout=60s >/dev/null 2>&1
route "${PLATFORM}" iso-platform-late
check "platform operator keeps working" "yes" "$(has_route "${PLATFORM}" iso-platform-late)"
check "second gateway keeps serving without its operator" "200" "$(second_code /iso/iso-second/get)"

echo
echo "passed=${pass} failed=${fail}"
[[ ${fail} -eq 0 ]]
