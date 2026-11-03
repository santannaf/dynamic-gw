#!/usr/bin/env bash
# Mede o tempo de propagação de uma rota nova: do `kubectl apply` da
# GatewayRoute CRD até TODOS os pods do gateway responderem 200 num GET
# na rota recém-criada.
#
# Modela o cenário "time aplica rota e quer usar imediatamente". A
# propagação inclui:
#   apply → apiserver → operator informer → debounce → ConfigMap rebuild →
#   gateway watcher pega update → reload do route table → rota ativa.
#
# Roda ITERATIONS iterações independentes (path único por iteração) e
# reporta estatísticas + veredito vs SLO_MS.
#
# Pode rodar isolado (em cluster idle) OU em paralelo com
# `scripts/k6/run-load-scenario.sh` em outro terminal — esse é o jeito de
# medir propagação SOB carga. Use LOCAL_PORT_BASE diferente do
# test-large-snapshot.sh (19080) pra evitar colisão.
#
# Variáveis (env):
#   ITERATIONS      Default 5      número de rotas distintas medidas
#   POLL_MS         Default 50     intervalo entre tentativas de curl
#   MAX_WAIT_MS     Default 15000  timeout por iteração
#   SLO_MS          Default 5000   propagação deveria caber em SLO_MS
#   CLEANUP         Default 1      deleta a GatewayRoute ao fim da iteração
#   GAP_MS          Default 1000   pausa entre iterações (volta pro baseline)
#   LOCAL_PORT_BASE Default 19180
#   HTTPBIN_URI     Default http://go-httpbin.platform.svc.cluster.local:8080
#
# Uso:
#   scripts/test-route-propagation.sh                          # 5 iter, SLO 5s
#   ITERATIONS=10 SLO_MS=2000 scripts/test-route-propagation.sh
#   CLEANUP=0 scripts/test-route-propagation.sh                # deixa rotas
#
# Em paralelo com cenário de carga:
#   # terminal 1: TEAMS=3 ROUTES=20000 scripts/k6/run-load-scenario.sh
#   # terminal 2 (após pre-flight do orquestrador subir o k6):
#   scripts/test-route-propagation.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

NAMESPACE="${NAMESPACE:-platform}"
GATEWAY_DEPLOYMENT="${GATEWAY_DEPLOYMENT:-dynamic-gateway}"
ITERATIONS="${ITERATIONS:-5}"
POLL_MS="${POLL_MS:-50}"
MAX_WAIT_MS="${MAX_WAIT_MS:-15000}"
SLO_MS="${SLO_MS:-5000}"
CLEANUP="${CLEANUP:-1}"
GAP_MS="${GAP_MS:-1000}"
LOCAL_PORT_BASE="${LOCAL_PORT_BASE:-19180}"
HTTPBIN_URI="${HTTPBIN_URI:-http://go-httpbin.platform.svc.cluster.local:8080}"

step() { echo; echo "==> $*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

# Milissegundos desde epoch. `date +%s%N` devolve sec+ns concatenados;
# dividir por 1_000_000 = ms.
now_ms() { echo $(($(date +%s%N) / 1000000)); }
# `sleep` aceita float em GNU/BSD coreutils; convertimos ms->s.
sleep_ms() { awk "BEGIN { system(\"sleep \" $1/1000) }"; }

pf_pids=()
applied_routes=()

cleanup() {
    local pid
    for pid in "${pf_pids[@]:-}"; do
        [ -n "${pid}" ] && kill "${pid}" 2>/dev/null || true
    done
    if [ "${CLEANUP}" = "1" ]; then
        local r
        for r in "${applied_routes[@]:-}"; do
            [ -n "${r}" ] || continue
            kubectl delete gatewayroute -n "${NAMESPACE}" "${r}" \
                --ignore-not-found >/dev/null 2>&1 || true
        done
    fi
}
trap cleanup EXIT INT TERM

# ----------------------------------------------------------------
# 1) Pre-flight
# ----------------------------------------------------------------
step "Pre-flight"
command -v kubectl >/dev/null || fail "kubectl não encontrado"
command -v curl    >/dev/null || fail "curl não encontrado"
command -v python3 >/dev/null || fail "python3 não encontrado"
command -v awk     >/dev/null || fail "awk não encontrado"

kubectl get deployment "${GATEWAY_DEPLOYMENT}" -n "${NAMESPACE}" >/dev/null \
    || fail "deployment ${GATEWAY_DEPLOYMENT}/${NAMESPACE} inacessível"

# Operator precisa estar de pé (ele é quem reconcilia CRD → ConfigMap)
kubectl get deployment gateway-route-operator -n "${NAMESPACE}" >/dev/null \
    || fail "deployment gateway-route-operator/${NAMESPACE} inacessível"

op_replicas=$(kubectl get deployment gateway-route-operator -n "${NAMESPACE}" \
    -o jsonpath='{.spec.replicas}')
if [ "${op_replicas}" = "0" ]; then
    fail "operator está em 0 réplicas – rotas novas nunca seriam propagadas"
fi

# CRD precisa existir
kubectl get crd gatewayroutes.platform.saca.pags >/dev/null \
    || fail "CRD gatewayroutes.platform.saca.pags não está instalado"

pods="$(kubectl get pods -n "${NAMESPACE}" \
    -l app.kubernetes.io/name="${GATEWAY_DEPLOYMENT}" \
    --field-selector=status.phase=Running \
    -o jsonpath='{range .items[?(@.status.containerStatuses[0].ready==true)]}{.metadata.name}{"\n"}{end}')"
pods="$(printf '%s\n' "${pods}" | sed '/^$/d')"
[ -z "${pods}" ] && fail "nenhum pod do gateway Ready"
pod_count=$(printf '%s\n' "${pods}" | wc -l | tr -d ' ')
echo "  ${pod_count} pod(s) Ready"
echo "  operator: ${op_replicas} réplica(s)"

# ----------------------------------------------------------------
# 2) Port-forward por pod
# ----------------------------------------------------------------
step "Port-forward por pod (base=${LOCAL_PORT_BASE})"
mapping=""
i=0
while IFS= read -r pod; do
    [ -z "${pod}" ] && continue
    port=$((LOCAL_PORT_BASE + i))
    kubectl port-forward -n "${NAMESPACE}" "pod/${pod}" "${port}:8080" >/dev/null 2>&1 &
    pf_pids+=("$!")
    # Espera o forward ficar Ready batendo no actuator
    ready=0
    for _ in $(seq 1 50); do
        if curl -fsS -o /dev/null "http://localhost:${port}/actuator/health" 2>/dev/null; then
            ready=1
            break
        fi
        sleep 0.2
    done
    [ "${ready}" -eq 1 ] || fail "port-forward de ${pod}:${port} não ficou pronto"
    mapping+="${pod}:${port}"$'\n'
    echo "  ${pod} -> localhost:${port}"
    i=$((i + 1))
done <<<"${pods}"

# ----------------------------------------------------------------
# 3) Loop de iterações
# ----------------------------------------------------------------
results_csv="/tmp/route-propagation.csv"
echo "iteration,pod,propagation_ms,status" >"${results_csv}"

step "Medindo ${ITERATIONS} iteração(ões) (SLO=${SLO_MS}ms, MAX_WAIT=${MAX_WAIT_MS}ms, POLL=${POLL_MS}ms)"

for iter in $(seq 1 "${ITERATIONS}"); do
    # nome único pra rota: timestamp em ms + sequencial. O nome do CRD
    # precisa ser um DNS-1123 label (sem ponto, max 63 chars, lower-case).
    ts="$(date +%s%3N)"
    route_name="prop-test-${ts}-${iter}"
    route_path="/${route_name}"

    echo
    echo "  [iter ${iter}/${ITERATIONS}] ${route_name}"

    # YAML inline. stripPrefix=1 -> request /${route_name}/get vira /get no
    # go-httpbin, que devolve 200 com JSON.
    crd_yaml=$(cat <<EOF
apiVersion: platform.saca.pags/v1alpha1
kind: GatewayRoute
metadata:
  name: ${route_name}
  namespace: ${NAMESPACE}
spec:
  path: ${route_path}/**
  targetUri: ${HTTPBIN_URI}
  stripPrefix: 1
  methods:
    - GET
  enabled: true
EOF
)

    # T0 = imediatamente antes do apply
    t0=$(now_ms)
    if ! echo "${crd_yaml}" | kubectl apply -f - >/dev/null 2>&1; then
        echo "    apply falhou; pulando iteração"
        continue
    fi
    applied_routes+=("${route_name}")

    # Polling: pra cada pod, registra o primeiro 200. Bash não tem array
    # associativa portável em todas as versões, mas 4+ tem. Aqui usamos
    # `declare -A` (Bash 4+; o k3d/scripts já dependem disso).
    declare -A pod_t
    while IFS=: read -r pod _port; do
        [ -z "${pod}" ] && continue
        pod_t["${pod}"]=0
    done <<<"${mapping}"

    pending=${pod_count}
    deadline=$((t0 + MAX_WAIT_MS))
    while [ "${pending}" -gt 0 ] && [ "$(now_ms)" -lt "${deadline}" ]; do
        while IFS=: read -r pod port; do
            [ -z "${pod}" ] && continue
            [ "${pod_t[${pod}]}" != "0" ] && continue
            code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 \
                "http://localhost:${port}${route_path}/get" 2>/dev/null || echo "000")
            if [ "${code}" = "200" ]; then
                pod_t["${pod}"]=$(( $(now_ms) - t0 ))
                pending=$((pending - 1))
            fi
        done <<<"${mapping}"
        [ "${pending}" -gt 0 ] && sleep_ms "${POLL_MS}"
    done

    # Relatório da iteração
    iter_max=0
    iter_status="OK"
    while IFS=: read -r pod _port; do
        [ -z "${pod}" ] && continue
        t=${pod_t["${pod}"]}
        if [ "${t}" = "0" ]; then
            echo "${iter},${pod},${MAX_WAIT_MS},timeout" >>"${results_csv}"
            echo "    ${pod}: TIMEOUT (>${MAX_WAIT_MS}ms) ❌"
            iter_status="TIMEOUT"
        else
            echo "${iter},${pod},${t},ok" >>"${results_csv}"
            [ "${t}" -gt "${iter_max}" ] && iter_max=${t}
            slo="✅"
            [ "${t}" -gt "${SLO_MS}" ] && slo="❌"
            echo "    ${pod}: ${t}ms ${slo}"
        fi
    done <<<"${mapping}"

    if [ "${iter_status}" = "OK" ]; then
        echo "    max iter: ${iter_max}ms (slo=${SLO_MS}ms)"
    fi
    unset pod_t

    # Cleanup imediato da rota da iteração pra não deixar lixo nem afetar
    # a próxima medida (o operator vai rebuildar o ConfigMap removendo essa).
    if [ "${CLEANUP}" = "1" ]; then
        kubectl delete gatewayroute -n "${NAMESPACE}" "${route_name}" \
            --ignore-not-found >/dev/null 2>&1 || true
        # remove do array de tracking pra cleanup global não rodar 2x
        applied_routes=("${applied_routes[@]/${route_name}}")
    fi

    sleep_ms "${GAP_MS}"
done

# ----------------------------------------------------------------
# 4) Estatísticas + veredito
# ----------------------------------------------------------------
step "Estatísticas e veredito"
python3 - "${results_csv}" "${SLO_MS}" "${MAX_WAIT_MS}" "${pod_count}" <<'PY'
import sys, csv
from collections import defaultdict

path, slo_str, maxwait_str, pod_count_str = sys.argv[1:]
slo = int(slo_str)
maxwait = int(maxwait_str)
pod_count = int(pod_count_str)

ok_times = []
timeouts = 0
by_iter = defaultdict(list)
with open(path) as f:
    rdr = csv.DictReader(f)
    for row in rdr:
        t = int(row["propagation_ms"])
        it = int(row["iteration"])
        if row["status"] == "ok":
            ok_times.append(t)
            by_iter[it].append(t)
        else:
            timeouts += 1

def pct(sorted_arr, p):
    if not sorted_arr:
        return 0
    k = max(0, min(len(sorted_arr) - 1, int(round(p * (len(sorted_arr) - 1)))))
    return sorted_arr[k]

if ok_times:
    ok_times.sort()
    n = len(ok_times)
    print(f"  amostras OK:        {n}")
    print(f"  amostras timeout:   {timeouts}")
    print(f"  min:                {min(ok_times)}ms")
    print(f"  avg:                {sum(ok_times)/n:.0f}ms")
    print(f"  p50:                {pct(ok_times, 0.50)}ms")
    print(f"  p95:                {pct(ok_times, 0.95)}ms")
    print(f"  p99:                {pct(ok_times, 0.99)}ms")
    print(f"  max:                {max(ok_times)}ms")
    print()
    # Veredito por iteração: cada iteração tem N pods; a propagação "do time"
    # é o MAX entre os pods (todos precisam estar respondendo). Comparamos
    # esse max com o SLO.
    iter_maxes = [max(v) for v in by_iter.values()]
    over_slo = [m for m in iter_maxes if m > slo]
    iter_ok = len(iter_maxes) - len(over_slo)
    print(f"  iterações respeitando SLO ({slo}ms): {iter_ok}/{len(iter_maxes)}")
    if not over_slo and timeouts == 0:
        print(f"  ✅ PASS – todas as iterações ficaram dentro do SLO sem timeout")
        sys.exit(0)
    else:
        if over_slo:
            print(f"  ❌ {len(over_slo)} iteração(ões) ultrapassaram o SLO: {over_slo}")
        if timeouts:
            print(f"  ❌ {timeouts} amostra(s) deram timeout (> {maxwait}ms)")
        sys.exit(1)
else:
    print(f"  amostras OK:      0")
    print(f"  amostras timeout: {timeouts}")
    print(f"  ❌ FAIL – nenhuma rota propagou; verifique operator e gateway")
    sys.exit(1)
PY
exit_code=$?

echo
echo "==> Detalhe completo em ${results_csv}"
exit ${exit_code}
