#!/usr/bin/env bash
# Banco do Keycloak no lab com PostgreSQL (k8s/sso/keycloak-postgres.yaml). É o
# default; KEYCLOAK_DB=oracle troca pelo scripts/db/oracle.sh.
#
#   scripts/db/postgres.sh up      # Secret keycloak-postgres (uma vez), StatefulSet com volume e a conexão do Keycloak
#   scripts/db/postgres.sh reset   # apaga todas as tabelas; o Keycloak reimporta o realm no próximo boot
#   scripts/db/postgres.sh empty   # sai com 0 se o Keycloak ainda não criou as tabelas
#   scripts/db/postgres.sh shell   # psql no banco (ex.: \dt, select name from realm;)
#   scripts/db/postgres.sh down    # remove o banco e o volume (o Secret keycloak-postgres fica)
#
# O reset é para o Keycloak parado: use scripts/keycloak/keycloak.sh reset.
set -euo pipefail
LOG_PREFIX=postgres
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"

POSTGRES_IMAGE="postgres:17.5-alpine"

sql() {
    kubectl -n "${NAMESPACE}" exec statefulset/keycloak-postgres -- \
        sh -c 'psql -q -U "$POSTGRES_USER" -d keycloak -v ON_ERROR_STOP=1 "$@"' psql "$@"
}

up() {
    ensure_image "${POSTGRES_IMAGE}"
    if ! kubectl -n "${NAMESPACE}" get secret keycloak-postgres >/dev/null 2>&1; then
        log "Generating Secret keycloak-postgres"
        kubectl -n "${NAMESPACE}" create secret generic keycloak-postgres \
            --from-literal=username=keycloak --from-literal=password="$(openssl rand -hex 24)" >/dev/null
    fi
    kubectl apply -f k8s/sso/keycloak-postgres.yaml >/dev/null
    wait_rollout statefulset/keycloak-postgres 180s
    publish_keycloak_db postgres "jdbc:postgresql://keycloak-postgres.${NAMESPACE}.svc.cluster.local:5432/keycloak" \
        keycloak-postgres
}

reset() {
    log "Dropping every Keycloak table"
    sql -c 'DROP SCHEMA public CASCADE; CREATE SCHEMA public;'
}

empty() {
    [[ "$(sql -tAc "select count(*) from information_schema.tables where table_schema = 'public' and table_name = 'realm'")" == "0" ]]
}

down() {
    kubectl delete -f k8s/sso/keycloak-postgres.yaml --ignore-not-found
    # O volume do StatefulSet não sai junto com ele.
    kubectl -n "${NAMESPACE}" delete pvc -l app.kubernetes.io/name=keycloak-postgres --ignore-not-found
}

case "${1:-}" in
    up) up ;;
    reset) reset ;;
    empty) empty ;;
    shell) kubectl -n "${NAMESPACE}" exec -it statefulset/keycloak-postgres -- sh -c 'psql -U "$POSTGRES_USER" -d keycloak' ;;
    down) down ;;
    *) echo "usage: $0 up|reset|empty|shell|down" >&2; exit 2 ;;
esac
