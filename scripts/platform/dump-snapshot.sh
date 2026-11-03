#!/usr/bin/env bash
set -euo pipefail

# Dump do snapshot atual do ConfigMap em YAML legível.
#
# Sabe lidar com os dois formatos:
#   - binaryData[routes.yaml.gz] (formato novo, default) -> base64 -d | gunzip
#   - data[routes.yaml]          (formato legado)         -> texto puro
#
# Uso:
#   scripts/dump-snapshot.sh                          # vai pro stdout
#   scripts/dump-snapshot.sh | head -20      # primeiras N linhas
#   scripts/dump-snapshot.sh > /tmp/x.yaml   # salva em arquivo

NAMESPACE="${NAMESPACE:-platform}"
CONFIGMAP="${CONFIGMAP:-gateway-routes}"
KEY="${KEY:-routes.yaml}"
GZIP_KEY="${KEY}.gz"

gz_b64="$(kubectl get cm "${CONFIGMAP}" -n "${NAMESPACE}" \
    -o jsonpath="{.binaryData.${GZIP_KEY//./\\.}}" 2>/dev/null || true)"
if [ -n "${gz_b64}" ]; then
    echo "${gz_b64}" | base64 -d | gunzip
    exit 0
fi

plain="$(kubectl get cm "${CONFIGMAP}" -n "${NAMESPACE}" \
    -o jsonpath="{.data.${KEY//./\\.}}" 2>/dev/null || true)"
if [ -n "${plain}" ]; then
    printf '%s\n' "${plain}"
    exit 0
fi

echo "ConfigMap ${NAMESPACE}/${CONFIGMAP} não tem '${GZIP_KEY}' em binaryData nem '${KEY}' em data" >&2
exit 1