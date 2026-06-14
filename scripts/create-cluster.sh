#!/usr/bin/env bash
set -euo pipefail

CLUSTER_NAME="${CLUSTER_NAME:-dynamic-gateway-lab}"
HOST_PORT="${HOST_PORT:-8080}"
NAMESPACE="${NAMESPACE:-platform}"

echo "[create-cluster] Creating k3d cluster '${CLUSTER_NAME}' mapping host port ${HOST_PORT} -> ingress 80"
k3d cluster create "${CLUSTER_NAME}" \
    -p "${HOST_PORT}:80@loadbalancer" \
    --wait

echo "[create-cluster] Ensuring namespace '${NAMESPACE}'"
kubectl create namespace "${NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -

echo "[create-cluster] Done. Reach the gateway via http://localhost:${HOST_PORT} once deployed."
