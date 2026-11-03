#!/usr/bin/env bash
# Diretório do lab: OpenLDAP no lugar do AD (k8s/sso/openldap.yaml). O Keycloak o
# federa por LDAPS, e o provider `ldap` do gateway lê o mesmo diretório.
#
#   scripts/ldap/openldap.sh up     # CA do lab e certificado (Secret openldap-tls, uma vez) e seed com a senha do lab
#   scripts/ldap/openldap.sh down   # remove o OpenLDAP e o seed (sso-secrets e openldap-tls ficam)
#
# O seed (k8s/sso/openldap-seed.ldif) só entra no primeiro boot: seed ou certificado
# novos recriam o pod, e o diretório volta ao seed.
set -euo pipefail
LOG_PREFIX=ldap
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"

OPENLDAP_IMAGE="osixia/openldap:1.5.0"

# CA do lab e certificado do OpenLDAP (LDAPS na 636). Apague o Secret para gerar outros.
ensure_tls() {
    if kubectl -n "${NAMESPACE}" get secret openldap-tls >/dev/null 2>&1; then
        return
    fi
    log "Generating the lab CA and the OpenLDAP certificate (Secret openldap-tls)"
    local dir host="openldap.${NAMESPACE}.svc.cluster.local"
    dir="$(mktemp -d)"
    openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=dynamic-gateway lab CA" \
        -keyout "${dir}/ca.key" -out "${dir}/ca.crt" 2>/dev/null
    openssl req -newkey rsa:2048 -nodes -subj "/CN=${host}" \
        -keyout "${dir}/tls.key" -out "${dir}/tls.csr" 2>/dev/null
    printf 'subjectAltName=DNS:%s,DNS:openldap,DNS:openldap.%s,DNS:openldap.%s.svc\nextendedKeyUsage=serverAuth\n' \
        "${host}" "${NAMESPACE}" "${NAMESPACE}" > "${dir}/ext.cnf"
    openssl x509 -req -in "${dir}/tls.csr" -CA "${dir}/ca.crt" -CAkey "${dir}/ca.key" -CAcreateserial \
        -days 825 -extfile "${dir}/ext.cnf" -out "${dir}/tls.crt" 2>/dev/null
    # Sem este arquivo a imagem gera um no boot, a cada pod novo.
    openssl dhparam -out "${dir}/dhparam.pem" 2048 2>/dev/null
    kubectl -n "${NAMESPACE}" create secret generic openldap-tls \
        --from-file=ca.crt="${dir}/ca.crt" --from-file=tls.crt="${dir}/tls.crt" \
        --from-file=tls.key="${dir}/tls.key" --from-file=dhparam.pem="${dir}/dhparam.pem" >/dev/null
    rm -rf "${dir}"
}

up() {
    ensure_sso_secrets
    ensure_image "${OPENLDAP_IMAGE}"
    ensure_tls
    local seed
    seed="$(sed "s/__DEMO_PASSWORD__/$(secret_value demo-password)/g" k8s/sso/openldap-seed.ldif)"
    printf '%s\n' "${seed}" \
        | kubectl -n "${NAMESPACE}" create secret generic openldap-seed \
            --from-file=50-tools.ldif=/dev/stdin --dry-run=client -o yaml \
        | kubectl apply -f - >/dev/null
    SEED_CHECKSUM="$( (printf '%s\n' "${seed}"; kubectl -n "${NAMESPACE}" get secret openldap-tls \
        -o jsonpath='{.data.tls\.crt}') | sha256sum | cut -c1-16)"
    render k8s/sso/openldap.yaml | kubectl apply -f - >/dev/null
    wait_rollout deployment/openldap 180s
}

down() {
    render k8s/sso/openldap.yaml | kubectl delete --ignore-not-found -f -
    kubectl -n "${NAMESPACE}" delete secret openldap-seed --ignore-not-found
}

case "${1:-}" in
    up) up ;;
    down) down ;;
    *) echo "usage: $0 up|down" >&2; exit 2 ;;
esac
