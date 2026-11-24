#!/usr/bin/env bash
set -euo pipefail

# As rotas em memória de cada pod do gateway (GET /internal/routes), chamando o
# pod direto por port-forward.
#
# Sem SSO, /internal/routes só responde a quem chega no pod sem passar pela
# borda: pelo nginx-lb, que acrescenta X-Forwarded-For, a resposta é 403. Com
# SSO, quem opera o gateway lê pela borda, com o próprio token ou cookie
# (TESTE_SSO.md).
#
# Uso:
#   scripts/platform/list-routes.sh                 # um JSON por pod
#   NAMESPACE=outro scripts/platform/list-routes.sh

NAMESPACE="${NAMESPACE:-platform}"
SELECTOR="${SELECTOR:-app.kubernetes.io/name=dynamic-gateway}"
PORT="${PORT:-18081}"

pods="$(kubectl -n "${NAMESPACE}" get pods -l "${SELECTOR}" --field-selector=status.phase=Running \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')"
if [ -z "${pods}" ]; then
    echo "Nenhum pod do gateway rodando em ${NAMESPACE} (${SELECTOR})." >&2
    exit 1
fi

forward=""
trap '[ -n "${forward}" ] && kill "${forward}" 2>/dev/null || true' EXIT

while read -r pod; do
    kubectl -n "${NAMESPACE}" port-forward "pod/${pod}" "${PORT}:8080" >/dev/null 2>&1 &
    forward=$!
    for _ in $(seq 1 50); do
        curl -fsS -o /dev/null "http://localhost:${PORT}/actuator/health" 2>/dev/null && break
        sleep 0.2
    done
    echo "== ${pod}"
    curl -fsS "http://localhost:${PORT}/internal/routes" | python3 -m json.tool
    kill "${forward}" 2>/dev/null || true
    wait "${forward}" 2>/dev/null || true
    forward=""
done <<<"${pods}"
