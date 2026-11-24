#!/usr/bin/env bash
set -euo pipefail

CLUSTER_NAME="${CLUSTER_NAME:-dynamic-gateway-lab}"

echo "[delete-cluster] Deleting k3d cluster '${CLUSTER_NAME}'"
k3d cluster delete "${CLUSTER_NAME}"
