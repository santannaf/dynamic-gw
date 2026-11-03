#!/usr/bin/env bash
# Lab completo num comando (make lab): cria o cluster k3d, compila o gateway e o
# operator em imagem nativa (nativeCompile), importa no cluster e sobe a plataforma
# v2 com o SSO. Rodar de novo reusa o cluster, na porta em que ele foi criado.
#
#   make lab linux   nativeCompile no host, com o GraalVM 25 dele (mais rápido); o host tem de ser Linux
#   make lab mac     nativeCompile dentro de um container Linux, para a arquitetura do Docker (arm64 no
#                    Apple Silicon): no macOS, o GraalVM gera binário de macOS, que não roda no cluster.
#                    Serve também para Linux sem GraalVM.
#   make lab         escolhe linux ou mac pelo sistema
#
# Variáveis:
#   HOST_PORT=18080      outra porta, na criação do cluster (default 8080)
#   KEYCLOAK_DB=oracle   Keycloak sobre Oracle 26ai Free (default PostgreSQL)
#   PROVIDER=ldap        login direto no OpenLDAP, sem Keycloak; PROVIDER=oauth: só tokens (default keycloak)
#   MODE=s3              snapshot das rotas no MinIO; MODE=standalone: v1, sem operator (default v2)
#   SKIP_BUILD=1         reusa as imagens locais, sem compilar
#   MICROSOFT=entra      botão "Entrar com Microsoft" no Entra ID real, com MICROSOFT_TENANT_ID,
#                        MICROSOFT_CLIENT_ID e MICROSOFT_CLIENT_SECRET (default: a Microsoft simulada)
#
# Sobe também as rotas /jp/... contra a API pública do JSONPlaceholder, uma por política
# (make jsonplaceholder-test): no v2, as de k8s/samples/routes-jsonplaceholder.yaml; no v1,
# as mesmas, que vêm no properties da imagem.
#
# Apagar tudo: make down.
set -euo pipefail

TARGET_OS="${1:-}"
REQUESTED_PORT="${HOST_PORT:-}"
LOG_PREFIX=lab
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"

MODE="${MODE:-v2}"
PROVIDER="${PROVIDER:-keycloak}"
GATEWAY_IMAGE="dynamic-gateway:local"
OPERATOR_IMAGE="dynamic-gateway-operator:local"
HTTPBIN_IMAGE="mccutchen/go-httpbin:latest"
CRD="gatewayroutes.platform.saca.pags"
STARTED="$(date +%s)"
CLUSTER_EXISTS=false
PLATFORM=""

fail() {
    echo "$*" >&2
    exit 2
}

architecture() {
    case "$1" in
        x86_64|amd64) echo amd64 ;;
        aarch64|arm64) echo arm64 ;;
        *) fail "Unsupported architecture '$1'" ;;
    esac
}

# Antes do build: variável errada ou porta ocupada não podem custar uma compilação nativa.
preflight() {
    local tools=(docker k3d kubectl curl openssl) missing=() tool image manifest system docker_arch
    system="$(uname -s)"
    if [[ -z "${TARGET_OS}" ]]; then
        case "${system}" in
            Linux) TARGET_OS=linux ;;
            Darwin) TARGET_OS=mac ;;
            *) fail "Unknown system '${system}': run make lab linux or make lab mac" ;;
        esac
    fi
    case "${TARGET_OS}" in
        linux)
            [[ "${system}" == "Linux" ]] || fail "make lab linux compiles on the host, which must be Linux: on macOS, run make lab mac"
            [[ "${SKIP_BUILD:-}" == "1" ]] || tools+=(java gcc)
            ;;
        mac) ;;
        *) fail "Usage: make lab [linux|mac] (got '${TARGET_OS}')" ;;
    esac

    for tool in "${tools[@]}"; do
        command -v "${tool}" >/dev/null 2>&1 || missing+=("${tool}")
    done
    [[ ${#missing[@]} -eq 0 ]] || fail "Missing tools: ${missing[*]}"
    docker info >/dev/null 2>&1 || fail "Docker is not running"

    # As imagens rodam nos nós do k3d, que têm a arquitetura do Docker (no Mac, a da VM).
    docker_arch="$(architecture "$(docker info --format '{{.Architecture}}')")"
    PLATFORM="linux/${docker_arch}"
    if [[ "${TARGET_OS}" == "linux" && "${SKIP_BUILD:-}" != "1" ]]; then
        [[ "$(architecture "$(uname -m)")" == "${docker_arch}" ]] \
            || fail "The host is $(uname -m) but Docker runs ${docker_arch}: run make lab mac"
        native_image >/dev/null || fail "make lab linux needs GraalVM 25 (native-image) on the host, with gcc and the zlib headers; without it, run make lab mac"
    fi

    case "${MODE}" in
        v2) manifest="k8s/sso/gateway-${PROVIDER}.yaml" ;;
        s3|standalone) manifest="k8s/sso/gateway-${MODE}-${PROVIDER}.yaml" ;;
        *) fail "MODE must be v2, s3 or standalone (got '${MODE}')" ;;
    esac
    case "${PROVIDER}" in
        keycloak|oauth) "${ROOT}/scripts/keycloak/keycloak.sh" check ;;
        ldap) ;;
        *) fail "PROVIDER must be keycloak, ldap or oauth (got '${PROVIDER}')" ;;
    esac
    [[ -f "${manifest}" ]] || fail "MODE=${MODE} has no manifest for PROVIDER=${PROVIDER} (${manifest})"

    if [[ "${SKIP_BUILD:-}" == "1" ]]; then
        for image in "${GATEWAY_IMAGE}" "${OPERATOR_IMAGE}"; do
            docker image inspect "${image}" >/dev/null 2>&1 || fail "SKIP_BUILD=1, but ${image} was never built: run without SKIP_BUILD"
        done
    fi

    if k3d cluster get "${CLUSTER_NAME}" >/dev/null 2>&1; then
        CLUSTER_EXISTS=true
        local port
        port="$(docker inspect -f '{{with index .HostConfig.PortBindings "80/tcp"}}{{(index . 0).HostPort}}{{end}}' \
            "k3d-${CLUSTER_NAME}-serverlb" 2>/dev/null || true)"
        [[ -n "${port}" ]] || fail "Cluster '${CLUSTER_NAME}' has no host port for the ingress: recreate it (make down && make lab)"
        if [[ -n "${REQUESTED_PORT}" && "${REQUESTED_PORT}" != "${port}" ]]; then
            fail "Cluster '${CLUSTER_NAME}' already exists on port ${port}, not ${REQUESTED_PORT}. Run without HOST_PORT to reuse it, or 'make down' to recreate it."
        fi
        HOST_PORT="${port}"
    elif (exec 3<>"/dev/tcp/127.0.0.1/${HOST_PORT}") 2>/dev/null; then
        fail "Port ${HOST_PORT} is taken: choose another one with HOST_PORT=<port>"
    fi
    export HOST_PORT
}

# Onde o plugin nativo do Gradle procura o native-image.
native_image() {
    local home
    for home in "${GRAALVM_HOME:-}" "${JAVA_HOME:-}"; do
        if [[ -n "${home}" && -x "${home}/bin/native-image" ]]; then
            echo "${home}/bin/native-image"
            return 0
        fi
    done
    command -v native-image
}

docker_build() {
    local module="$1" image="$2" target="$3"
    docker buildx build --provenance=false --sbom=false --platform "${PLATFORM}" --target "${target}" \
        -f "${module}/Dockerfile" -t "${image}" --load .
}

build_images() {
    if [[ "${SKIP_BUILD:-}" == "1" ]]; then
        log "Reusing the local images ${GATEWAY_IMAGE} and ${OPERATOR_IMAGE}"
        return
    fi
    if [[ "${TARGET_OS}" == "linux" ]]; then
        log "nativeCompile on the host ($(native_image)): gateway and operator"
        ./gradlew :gateway:nativeCompile :operator:nativeCompile -x test
        log "Packing the native binaries into ${PLATFORM} images"
        docker_build gateway "${GATEWAY_IMAGE}" host
        docker_build operator "${OPERATOR_IMAGE}" host
    else
        log "nativeCompile inside a Linux container, for ${PLATFORM}: gateway and operator (a few minutes each)"
        docker_build gateway "${GATEWAY_IMAGE}" container
        docker_build operator "${OPERATOR_IMAGE}" container
    fi
}

ensure_cluster() {
    if [[ "${CLUSTER_EXISTS}" == "true" ]]; then
        log "Reusing cluster '${CLUSTER_NAME}' on port ${HOST_PORT}"
        k3d cluster start "${CLUSTER_NAME}" >/dev/null
        k3d kubeconfig merge "${CLUSTER_NAME}" --kubeconfig-merge-default --kubeconfig-switch-context >/dev/null
    else
        "${ROOT}/scripts/cluster/create-cluster.sh"
    fi
}

import_images() {
    log "Importing ${GATEWAY_IMAGE} and ${OPERATOR_IMAGE} into k3d cluster '${CLUSTER_NAME}'"
    k3d image import "${GATEWAY_IMAGE}" "${OPERATOR_IMAGE}" -c "${CLUSTER_NAME}" >/dev/null
    ensure_image "${HTTPBIN_IMAGE}"
}

# O Deployment do gateway vem com o profile de SSO, do scripts/sso/deploy-sso.sh.
platform_v2() {
    log "Platform v2: CRD, operator, the gateway's Service, RBAC and Ingress, sample routes"
    kubectl apply -f k8s/namespace.yaml >/dev/null
    kubectl apply -f "k8s/crd/${CRD}.yaml" >/dev/null
    kubectl wait --for condition=established "crd/${CRD}" --timeout=60s >/dev/null
    kubectl -n "${NAMESPACE}" apply -f k8s/operator/service-account.yaml -f k8s/operator/rbac.yaml \
        -f k8s/operator/deployment.yaml >/dev/null
    kubectl -n "${NAMESPACE}" apply -f k8s/gateway/rbac.yaml -f k8s/gateway/service.yaml \
        -f k8s/gateway/ingress.yaml >/dev/null
    kubectl -n "${NAMESPACE}" apply -f k8s/samples/route-httpbin.yaml -f k8s/samples/route-anything.yaml \
        -f k8s/samples/routes-jsonplaceholder.yaml >/dev/null
    wait_rollout deployment/gateway-route-operator 180s >/dev/null
}

# Cluster reaproveitado mantém o banco do Keycloak, a não ser que KEYCLOAK_DB diga outro:
# trocar de banco zera o realm.
keep_keycloak_db() {
    [[ -z "${KEYCLOAK_DB:-}" && "${CLUSTER_EXISTS}" == "true" ]] || return 0
    local current
    current="$(kubectl -n "${NAMESPACE}" get configmap keycloak-db -o jsonpath='{.data.KC_DB}' 2>/dev/null || true)"
    if [[ -n "${current}" ]]; then
        export KEYCLOAK_DB="${current}"
        log "Keeping the cluster's Keycloak database: ${KEYCLOAK_DB}"
    fi
}

# Mesma tag, imagem nova: pod que já rodava só pega a imagem depois de reiniciar.
restart_with_new_images() {
    [[ "${CLUSTER_EXISTS}" == "true" && "${SKIP_BUILD:-}" != "1" ]] || return 0
    local deployment
    for deployment in gateway-route-operator dynamic-gateway; do
        if kubectl -n "${NAMESPACE}" get "deployment/${deployment}" >/dev/null 2>&1; then
            log "Restarting ${deployment} on the new image"
            kubectl -n "${NAMESPACE}" rollout restart "deployment/${deployment}" >/dev/null
            wait_rollout "deployment/${deployment}" 300s >/dev/null
        fi
    done
}

summary() {
    local elapsed=$(( $(date +%s) - STARTED )) test port_prefix=""
    case "${MODE}:${PROVIDER}" in
        standalone:*) test=standalone-sso-test ;;
        s3:*) test=s3-sso-test ;;
        *:ldap) test=ldap-test ;;
        *:oauth) test=oauth-test ;;
        *) test=sso-test ;;
    esac
    [[ "${HOST_PORT}" == "8080" ]] || port_prefix="HOST_PORT=${HOST_PORT} "
    echo
    log "Lab ready in $((elapsed / 60))m$((elapsed % 60))s (${TARGET_OS} build, ${PLATFORM} images). kubectl context: $(kubectl config current-context)"
    echo "      Tests:              ${port_prefix}make ${test}; ${port_prefix}make policies-test"
    echo "      Real API:           http://localhost:${HOST_PORT}/jp/posts/1 (${port_prefix}make jsonplaceholder-test)"
    echo "      Delete everything:  make down"
}

preflight
build_images
ensure_cluster
keep_keycloak_db
import_images
[[ "${MODE}" == "standalone" ]] || platform_v2
PROVIDER="${PROVIDER}" MODE="${MODE}" "${ROOT}/scripts/sso/deploy-sso.sh"
restart_with_new_images
summary
