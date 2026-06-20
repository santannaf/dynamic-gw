#!/usr/bin/env bash
# Monitora o consumo de memória dos pods do dynamic-gateway durante um
# experimento de carga. Útil pra comparar baseline antes/depois de
# otimizações (ex: lastLoadedRoutes -> hash) ou pra confirmar onde está
# o pico durante um reload.
#
# Modos:
#   1) Sample contínuo via `kubectl top pod` (funciona com native image).
#      Mostra RSS atual a cada INTERVAL segundos por DURATION segundos.
#   2) Class histogram one-shot via `jcmd` (SÓ funciona se o gateway está
#      rodando como bootJar, ou native compilado com `-H:+AllowVMInspection`).
#      Lista as classes que mais consomem heap.
#
# Uso:
#   scripts/measure-gateway-memory.sh                       # modo 1, 5min @ 5s
#   INTERVAL=2 DURATION=120 scripts/measure-gateway-memory.sh
#   scripts/measure-gateway-memory.sh --histogram           # tenta modo 2
#   scripts/measure-gateway-memory.sh --histogram --top 30  # top 30 classes
#
# Receita pra confirmar o ganho do lastLoadedRoutes -> hash:
#   1) Antes da mudança, com o cluster idle (~2 rotas):
#      scripts/measure-gateway-memory.sh --duration 30      # baseline
#   2) make large-snapshot-test (push de 60k)
#   3) Aguarda o snapshot convergir e o operator restaurar o snapshot real:
#      scripts/measure-gateway-memory.sh --duration 60      # pós-stress
#   4) Aplica o patch do hash, rebuilda nativo, redeploya
#   5) Repete 1-3 — compare os RSS

set -uo pipefail

NAMESPACE="${NAMESPACE:-platform}"
DEPLOYMENT="${DEPLOYMENT:-dynamic-gateway}"
INTERVAL="${INTERVAL:-5}"
DURATION="${DURATION:-300}"
HISTOGRAM=0
TOP_N=20

while [ $# -gt 0 ]; do
    case "$1" in
        --histogram) HISTOGRAM=1 ;;
        --top)       TOP_N="$2"; shift ;;
        --interval)  INTERVAL="$2"; shift ;;
        --duration)  DURATION="$2"; shift ;;
        --namespace) NAMESPACE="$2"; shift ;;
        -h|--help)
            sed -n '2,/^$/p' "$0" | sed 's/^# \?//'
            exit 0 ;;
        *) echo "Flag desconhecida: $1" >&2; exit 2 ;;
    esac
    shift
done

step() { echo; echo "==> $*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

# ----------------------------------------------------------------
# Pre-flight
# ----------------------------------------------------------------
command -v kubectl >/dev/null || fail "kubectl não encontrado"

if ! kubectl top node >/dev/null 2>&1; then
    cat >&2 <<EOF
AVISO: \`kubectl top\` não respondeu. Em k3d/kind, o metrics-server pode
não estar instalado ou pronto. Instale com:
   kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
   kubectl -n kube-system patch deployment metrics-server --type='json' -p='[
     { "op":"add", "path":"/spec/template/spec/containers/0/args/-",
       "value":"--kubelet-insecure-tls" } ]'
   kubectl -n kube-system rollout status deployment metrics-server
EOF
fi

# ----------------------------------------------------------------
# Modo histogram (one-shot, requer jcmd no container)
# ----------------------------------------------------------------
if [ "${HISTOGRAM}" = "1" ]; then
    step "Class histogram one-shot (top ${TOP_N})"
    pod=$(kubectl -n "${NAMESPACE}" get pod -l "app.kubernetes.io/name=${DEPLOYMENT}" \
        -o jsonpath='{.items[0].metadata.name}')
    [ -z "${pod}" ] && fail "nenhum pod do gateway encontrado"
    echo "  pod alvo: ${pod}"

    # Detecta se há jcmd. Em native image sem `-H:+AllowVMInspection`,
    # jcmd não vai estar instalado / não vai funcionar.
    if ! kubectl -n "${NAMESPACE}" exec "${pod}" -- which jcmd >/dev/null 2>&1; then
        cat >&2 <<EOF
FAIL: \`jcmd\` não disponível no pod ${pod}.
       Provavelmente o gateway foi compilado como native image sem
       \`-H:+AllowVMInspection\`. Pra rodar o histograma:
         1) Rode o gateway em modo bootJar (java -jar gateway.jar)
         2) OU rebuilde nativo adicionando ao gateway/build.gradle:
              graalvmNative.binaries.main.buildArgs.add('-H:+AllowVMInspection')
EOF
        exit 1
    fi

    kubectl -n "${NAMESPACE}" exec "${pod}" -- jcmd 1 GC.class_histogram \
        | head -$((TOP_N + 4))
    exit 0
fi

# ----------------------------------------------------------------
# Modo sample contínuo (default)
# ----------------------------------------------------------------
step "Monitorando memória de ${DEPLOYMENT}/${NAMESPACE} (intervalo=${INTERVAL}s, total=${DURATION}s)"
echo "  Pressione Ctrl+C pra interromper"
echo
printf "%-10s %-50s %-10s %-10s\n" "hora" "pod" "CPU" "MEM"
printf "%-10s %-50s %-10s %-10s\n" "----" "---" "---" "---"

end=$(( $(date +%s) + DURATION ))
samples=0
while [ "$(date +%s)" -lt "${end}" ]; do
    ts=$(date +%H:%M:%S)
    out=$(kubectl -n "${NAMESPACE}" top pod \
        -l "app.kubernetes.io/name=${DEPLOYMENT}" \
        --containers --no-headers 2>/dev/null || true)
    if [ -z "${out}" ]; then
        printf "%-10s %-50s %-10s %-10s\n" "${ts}" "(sem dados — metrics-server pronto?)" "-" "-"
    else
        # campos do `top pod --containers`: POD POD_CONTAINER CPU(cores) MEMORY(bytes)
        while IFS= read -r line; do
            [ -z "${line}" ] && continue
            pod=$(echo "${line}" | awk '{print $1}')
            cpu=$(echo "${line}" | awk '{print $3}')
            mem=$(echo "${line}" | awk '{print $4}')
            printf "%-10s %-50s %-10s %-10s\n" "${ts}" "${pod}" "${cpu}" "${mem}"
        done <<<"${out}"
    fi
    samples=$((samples + 1))
    sleep "${INTERVAL}"
done

echo
echo "==> Coletadas ${samples} amostras em ~${DURATION}s. Compare com a baseline."
