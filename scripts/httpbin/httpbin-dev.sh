#!/usr/bin/env bash
# go-httpbin num namespace que já existe em outro cluster, como backend de teste atrás do
# gateway. Passa no Pod Security restricted: a imagem roda como o usuário 65532.
#
#   KUBE_CONTEXT=<contexto> NAMESPACE=<namespace> scripts/httpbin/httpbin-dev.sh up
#   KUBE_CONTEXT=<contexto> NAMESPACE=<namespace> scripts/httpbin/httpbin-dev.sh down
#
# HTTPBIN_IMAGE troca a imagem, para o cluster que só puxa de um registry interno, e
# IMAGE_PULL_SECRET dá ao pod o Secret de pull desse registry. Como o keycloak-dev.sh, não usa
# o common.sh: copiado sozinho para outra máquina, roda igual.
set -euo pipefail

HTTPBIN_IMAGE="${HTTPBIN_IMAGE:-mccutchen/go-httpbin:2.26.0}"

usage() {
    echo "usage: KUBE_CONTEXT=<contexto> NAMESPACE=<namespace> $0 up|down" >&2
    exit 2
}

# Sem o contexto, o kubectl iria para o contexto atual da máquina.
[[ -n "${KUBE_CONTEXT:-}" && -n "${NAMESPACE:-}" ]] || usage

kube() {
    kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" "$@"
}

log() {
    echo "[httpbin-dev] $*"
}

image_pull_secrets() {
    if [[ -n "${IMAGE_PULL_SECRET:-}" ]]; then
        echo "      imagePullSecrets:"
        echo "        - name: ${IMAGE_PULL_SECRET}"
    fi
}

manifest() {
    cat <<YAML
apiVersion: apps/v1
kind: Deployment
metadata:
  name: go-httpbin
  labels:
    app.kubernetes.io/name: go-httpbin
    app.kubernetes.io/part-of: dynamic-gateway-control-plane
spec:
  replicas: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: go-httpbin
  template:
    metadata:
      labels:
        app.kubernetes.io/name: go-httpbin
    spec:
$(image_pull_secrets)
      securityContext:
        runAsNonRoot: true
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: httpbin
          image: ${HTTPBIN_IMAGE}
          ports:
            - name: http
              containerPort: 8080
          readinessProbe:
            httpGet:
              path: /status/200
              port: http
          resources:
            requests:
              cpu: 50m
              memory: 32Mi
            limits:
              cpu: 1000m
              memory: 128Mi
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: ["ALL"]
---
apiVersion: v1
kind: Service
metadata:
  name: go-httpbin
  labels:
    app.kubernetes.io/name: go-httpbin
    app.kubernetes.io/part-of: dynamic-gateway-control-plane
spec:
  selector:
    app.kubernetes.io/name: go-httpbin
  ports:
    - name: http
      port: 8080
      targetPort: http
YAML
}

# Pod recusado (Pod Security, quota) só aparece nos eventos: o rollout apenas estoura o tempo.
wait_ready() {
    kube rollout status deployment/go-httpbin --timeout=180s && return
    kube get events --sort-by=.lastTimestamp | tail -20 >&2 || true
    exit 1
}

up() {
    log "Image: ${HTTPBIN_IMAGE}"
    manifest | kube apply -f -
    wait_ready
    log "Ready at http://go-httpbin.${NAMESPACE}.svc.cluster.local:8080"
    log "  GET /get              echoes the query and the headers (what the gateway forwards)"
    log "  POST /post            echoes the body too"
    log "  GET /status/<code>    answers with that status"
    log "From your machine:"
    log "  kubectl --context ${KUBE_CONTEXT} -n ${NAMESPACE} port-forward svc/go-httpbin 18081:8080"
    log "  curl http://localhost:18081/get"
}

down() {
    manifest | kube delete --ignore-not-found -f -
}

case "${1:-}" in
    up) up ;;
    down) down ;;
    *) usage ;;
esac
