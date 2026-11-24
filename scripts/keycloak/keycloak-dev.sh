#!/usr/bin/env bash
# Keycloak e PostgreSQL de scripts/keycloak/dev/ num namespace que já existe em outro
# cluster, só com o realm master e sem diretório. Passam no Pod Security restricted, em
# que o keycloak.sh não sobe: o OpenLDAP do lab só roda como root.
#
#   KUBE_CONTEXT=<contexto> NAMESPACE=<namespace> scripts/keycloak/keycloak-dev.sh up
#   KUBE_CONTEXT=<contexto> NAMESPACE=<namespace> scripts/keycloak/keycloak-dev.sh down   # apaga também o banco
#
# O up gera os Secrets keycloak-postgres e keycloak-admin quando não existem, e o down
# não os apaga. Num banco que já tem admin, a senha do Secret keycloak-admin não vale.
set -euo pipefail

usage() {
    echo "usage: KUBE_CONTEXT=<contexto> NAMESPACE=<namespace> $0 up|down" >&2
    exit 2
}

# Sem os dois, o kubectl iria para o contexto atual da máquina e para o namespace platform do lab.
[[ -n "${KUBE_CONTEXT:-}" && -n "${NAMESPACE:-}" ]] || usage

LOG_PREFIX=keycloak-dev
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"

DEV=scripts/keycloak/dev

ensure_secret() {
    local name="$1"
    shift
    kubectl -n "${NAMESPACE}" get secret "${name}" >/dev/null 2>&1 && return
    log "Generating Secret ${name}"
    kubectl -n "${NAMESPACE}" create secret generic "${name}" "$@" >/dev/null
}

# Pod recusado (Pod Security, quota) só aparece nos eventos: o rollout apenas estoura o tempo.
wait_ready() {
    wait_rollout "$1" "$2" && return
    kubectl -n "${NAMESPACE}" get events --sort-by=.lastTimestamp | tail -20 >&2 || true
    exit 1
}

up() {
    ensure_secret keycloak-postgres --from-literal=username=keycloak --from-literal=password="$(openssl rand -hex 24)"
    ensure_secret keycloak-admin --from-literal=password="$(openssl rand -hex 16)"
    kubectl -n "${NAMESPACE}" apply -f "${DEV}/postgres.yaml" >/dev/null
    wait_ready statefulset/keycloak-postgres 300s
    kubectl -n "${NAMESPACE}" apply -f "${DEV}/keycloak.yaml" >/dev/null
    wait_ready deployment/keycloak 600s
    log "Console at http://localhost:18080/auth/admin (user admin) through"
    log "  kubectl --context ${KUBE_CONTEXT} -n ${NAMESPACE} port-forward svc/keycloak 18080:8080"
    log "Admin password:"
    log "  kubectl --context ${KUBE_CONTEXT} -n ${NAMESPACE} get secret keycloak-admin -o jsonpath='{.data.password}' | base64 -d"
}

down() {
    kubectl -n "${NAMESPACE}" delete --ignore-not-found -f "${DEV}/keycloak.yaml" -f "${DEV}/postgres.yaml"
    # O volume do StatefulSet não sai junto com ele.
    kubectl -n "${NAMESPACE}" delete pvc -l app.kubernetes.io/name=keycloak-postgres --ignore-not-found
}

case "${1:-}" in
    up) up ;;
    down) down ;;
    *) usage ;;
esac
