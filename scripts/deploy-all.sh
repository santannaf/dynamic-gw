#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

CLUSTER_NAME="${CLUSTER_NAME:-dynamic-gateway-lab}"
NAMESPACE="${NAMESPACE:-platform}"
GATEWAY_IMAGE="${GATEWAY_IMAGE:-dynamic-gateway:local}"
OPERATOR_IMAGE="${OPERATOR_IMAGE:-dynamic-gateway-operator:local}"

echo "[deploy] Building jars"
./gradlew :gateway:bootJar :operator:bootJar -x test

echo "[deploy] Building images"
docker build -f gateway/Dockerfile  -t "${GATEWAY_IMAGE}"  .
docker build -f operator/Dockerfile -t "${OPERATOR_IMAGE}" .

echo "[deploy] Importing images into k3d cluster '${CLUSTER_NAME}'"
k3d image import "${GATEWAY_IMAGE}" "${OPERATOR_IMAGE}" -c "${CLUSTER_NAME}"

echo "[deploy] Applying namespace + CRD"
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/crd/gatewayroutes.platform.saca.pags.yaml

echo "[deploy] Applying RBAC"
kubectl apply -f k8s/operator/service-account.yaml
kubectl apply -f k8s/operator/rbac.yaml
kubectl apply -f k8s/gateway/rbac.yaml

echo "[deploy] Applying gateway"
kubectl apply -f k8s/gateway/deployment.yaml
kubectl apply -f k8s/gateway/service.yaml
kubectl apply -f k8s/gateway/ingress.yaml

echo "[deploy] Applying operator"
kubectl apply -f k8s/operator/deployment.yaml

echo "[deploy] Waiting for rollouts"
kubectl rollout status deployment/dynamic-gateway        -n "${NAMESPACE}" --timeout=180s
kubectl rollout status deployment/gateway-route-operator -n "${NAMESPACE}" --timeout=180s

echo "[deploy] Done. Gateway: http://localhost:${HOST_PORT:-8080}"
