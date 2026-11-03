#!/usr/bin/env bash
set -euo pipefail

CLUSTER_NAME="${CLUSTER_NAME:-dynamic-gateway-lab}"
HOST_PORT="${HOST_PORT:-8080}"
NAMESPACE="${NAMESPACE:-platform}"
# Timezone aplicado aos nós do k3d (controla logs do próprio k3s/containerd).
# Pods herdam SOMENTE se a env TZ for setada no PodSpec deles também — os
# manifests em k8s/gateway e k8s/operator já fazem isso.
TZ_CLUSTER="${TZ_CLUSTER:-America/Sao_Paulo}"

echo "[create-cluster] Creating k3d cluster '${CLUSTER_NAME}' mapping host port ${HOST_PORT} -> ingress 80 (TZ=${TZ_CLUSTER})"
k3d cluster create "${CLUSTER_NAME}" \
    -p "${HOST_PORT}:80@loadbalancer" \
    --env "TZ=${TZ_CLUSTER}@server:*;agent:*" \
    --wait

echo "[create-cluster] Ensuring namespace '${NAMESPACE}'"
kubectl create namespace "${NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -

echo "[create-cluster] Done. Reach the gateway via http://localhost:${HOST_PORT} once deployed."
