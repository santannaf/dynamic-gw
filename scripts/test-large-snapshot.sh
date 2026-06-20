#!/usr/bin/env bash
set -euo pipefail

# Stress test: empurra um snapshot grande (default ~1500 rotas, ~10k linhas
# de YAML) direto no ConfigMap e mede o tempo de reload em cada pod do
# gateway.
#
# Por que pausar o operator: ele observa GatewayRoutes e republica o
# snapshot a cada reconcile. Se deixarmos rodando, ele sobrescreve o
# snapshot de teste segundos depois. Religamos no final, o que naturalmente
# expulsa o snapshot de teste e devolve o cluster ao estado real.
#
# Por que server-side apply: a annotation `kubectl.kubernetes.io/last-applied-configuration`
# do client-side apply duplicaria o YAML inteiro dentro da própria annotation,
# o que não escala (e o apiserver tem um limite de ~256 KiB para metadata).
# Server-side apply troca isso por managedFields incremental.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

NAMESPACE="${NAMESPACE:-platform}"
GATEWAY_DEPLOYMENT="${GATEWAY_DEPLOYMENT:-dynamic-gateway}"
OPERATOR_DEPLOYMENT="${OPERATOR_DEPLOYMENT:-gateway-route-operator}"
CONFIGMAP="${CONFIGMAP:-gateway-routes}"
KEY="${KEY:-routes.yaml}"
ROUTES="${ROUTES:-1500}"
LOCAL_PORT_BASE="${LOCAL_PORT_BASE:-19080}"
WAIT_RELOAD_SECS="${WAIT_RELOAD_SECS:-90}"
# Quando 1, o snapshot gerado inclui também httpbin-route e anything-route
# (mesma definição do k8s/samples + TESTE_LOCAL.md). Útil para o cenário do
# k6 stress test em scripts/k6/load-during-large-snapshot.js, onde queremos
# que as duas rotas baseline continuem servindo durante o reload pesado.
INCLUDE_BASELINE="${INCLUDE_BASELINE:-0}"

# Número de rotas baseline que o snapshot vai ganhar quando INCLUDE_BASELINE=1.
# Mantemos isso explícito porque a etapa "esperar todos os pods convergirem"
# checa contagem exata.
if [ "${INCLUDE_BASELINE}" = "1" ]; then
    BASELINE_COUNT=2
else
    BASELINE_COUNT=0
fi
# TEAMS=N simula N times entregando ROUTES rotas cada, simultaneamente
# (modela o estado final que o operator produziria após consolidar N
# conjuntos de GatewayRoute CRDs aplicados em paralelo).  Cada time tem
# prefixo distinto (team-1-route-*, team-2-route-*, ...) e contribui
# ROUTES rotas únicas, sem colisão de path.  Default 1 mantém o
# comportamento original (1 conjunto = stress-route-*).
TEAMS="${TEAMS:-1}"
if [ "${TEAMS}" -lt 1 ]; then
    echo "FAIL: TEAMS precisa ser >= 1" >&2
    exit 1
fi
TOTAL_STRESS_ROUTES=$((ROUTES * TEAMS))
EXPECTED_ROUTES=$((TOTAL_STRESS_ROUTES + BASELINE_COUNT))

step() { echo; echo "==> $*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

# Estado original do operator pra restaurar no final
ORIGINAL_OPERATOR_REPLICAS=""
pf_pids=()

cleanup() {
    for pid in "${pf_pids[@]:-}"; do
        if [ -n "${pid}" ] && kill -0 "${pid}" 2>/dev/null; then
            kill "${pid}" 2>/dev/null || true
        fi
    done
    if [ -n "${ORIGINAL_OPERATOR_REPLICAS}" ] && [ "${KEEP_SNAPSHOT:-0}" != "1" ]; then
        echo
        echo "==> Restaurando operator para ${ORIGINAL_OPERATOR_REPLICAS} réplica(s) (vai republicar o snapshot real)"
        kubectl scale deployment/"${OPERATOR_DEPLOYMENT}" -n "${NAMESPACE}" \
            --replicas="${ORIGINAL_OPERATOR_REPLICAS}" >/dev/null
    elif [ "${KEEP_SNAPSHOT:-0}" = "1" ]; then
        echo
        echo "==> KEEP_SNAPSHOT=1 - operator ficou em 0 réplicas e o snapshot de teste continua no ConfigMap."
        echo "    Pra restaurar manualmente quando terminar:"
        echo "      kubectl scale deployment/${OPERATOR_DEPLOYMENT} -n ${NAMESPACE} --replicas=${ORIGINAL_OPERATOR_REPLICAS}"
    fi
    rm -f /tmp/large-snapshot.yaml /tmp/large-snapshot.yaml.gz /tmp/configmap-large.yaml /tmp/large-snapshot.log
}
trap cleanup EXIT

step "1. Pausando o operator pra ele não sobrescrever o snapshot de teste"
ORIGINAL_OPERATOR_REPLICAS="$(kubectl get deployment/"${OPERATOR_DEPLOYMENT}" \
    -n "${NAMESPACE}" -o jsonpath='{.spec.replicas}')"
echo "   operator estava em ${ORIGINAL_OPERATOR_REPLICAS} réplica(s)"
kubectl scale deployment/"${OPERATOR_DEPLOYMENT}" -n "${NAMESPACE}" --replicas=0 >/dev/null
kubectl wait --for=delete pod -l app.kubernetes.io/name="${OPERATOR_DEPLOYMENT}" \
    -n "${NAMESPACE}" --timeout=60s 2>/dev/null || true

step "2. Gerando snapshot com ${TOTAL_STRESS_ROUTES} rotas de stress (${TEAMS} time(s) x ${ROUTES})$( [ "${INCLUDE_BASELINE}" = "1" ] && echo " + ${BASELINE_COUNT} baseline" ) + gzip"
python3 - "${ROUTES}" /tmp/large-snapshot.yaml.gz "${INCLUDE_BASELINE}" "${TEAMS}" <<'PY'
import gzip, sys

n = int(sys.argv[1])
out_path = sys.argv[2]
include_baseline = sys.argv[3] == "1"
teams = int(sys.argv[4])

lines = ['version: "stress-test"', 'generatedAt: "2026-06-18T00:00:00Z"', 'routes:']

# Baseline opt-in: garante que /httpbin e /anything continuam roteáveis
# durante o reload pesado (cenário do k6 load-during-large-snapshot.js).
# Definições espelhadas de k8s/samples/route-httpbin.yaml e do snippet
# anything-route documentado em TESTE_LOCAL.md.
if include_baseline:
    baseline = [
        ("httpbin-route",  "/httpbin/**",  1, ["GET", "POST"]),
        ("anything-route", "/anything/**", 0, ["GET"]),
    ]
    for rid, path, strip, methods in baseline:
        lines.append(f"  - id: {rid}")
        lines.append(f"    path: {path}")
        lines.append(f"    targetUri: http://go-httpbin.platform.svc.cluster.local:8080")
        lines.append(f"    stripPrefix: {strip}")
        lines.append(f"    methods:")
        for m in methods:
            lines.append(f"      - {m}")
        lines.append(f"    enabled: true")

# teams == 1 mantém o id legado (stress-route-NNNNN) para não quebrar
# comparações com runs anteriores. teams >= 2 usa prefixo team-N-route-*
# para simular múltiplos times entregando conjuntos disjuntos de rotas.
if teams == 1:
    prefixes = [("stress", "stress")]
else:
    prefixes = [(f"team-{t}", f"team-{t}") for t in range(1, teams + 1)]

for id_prefix, path_prefix in prefixes:
    for i in range(1, n + 1):
        rid = f"{id_prefix}-route-{i:05d}"
        lines.append(f"  - id: {rid}")
        lines.append(f"    path: /{path_prefix}/{i:05d}/**")
        lines.append(f"    targetUri: http://{id_prefix}-svc-{i:05d}.platform.svc.cluster.local:8080")
        lines.append(f"    stripPrefix: 2")
        lines.append(f"    methods:")
        lines.append(f"      - GET")
        lines.append(f"    enabled: true")
raw = ("\n".join(lines) + "\n").encode("utf-8")
gz = gzip.compress(raw, compresslevel=6)

with open(out_path, "wb") as fh:
    fh.write(gz)

print(f"raw_bytes={len(raw)} gzip_bytes={len(gz)} ratio={len(gz)/len(raw):.3f}", file=sys.stderr)
PY
# `wc -c < file` é POSIX e devolve só o número de bytes — funciona em Linux
# (GNU coreutils) e macOS (BSD) sem precisar detectar plataforma. A versão
# anterior tentava `stat -f '%z'` (BSD) com fallback pra `stat -c '%s'` (GNU),
# mas no Linux `-f` significa `--file-system`, então o primeiro comando NÃO
# falhava — devolvia o bloco "File: ... ID: ... Type: ..." e o `||` nunca
# disparava, deixando gz_bytes com texto e quebrando o `$((... / 1024))` aqui.
gz_bytes=$(wc -c < /tmp/large-snapshot.yaml.gz)
gz_kib=$((gz_bytes / 1024))
echo "   snapshot.gz: ${gz_bytes} bytes (~${gz_kib} KiB)"

# binaryData entra como base64 e tem +33% overhead. Limite efetivo do
# ConfigMap inteiro é 1 MiB. Estimamos com folga aqui.
b64_estimate=$(( (gz_bytes * 4 + 2) / 3 ))
b64_kib=$((b64_estimate / 1024))
echo "   base64 estimado: ~${b64_kib} KiB"
limit_kib=1000   # 1 MiB com folga pra metadata
if [ "${b64_kib}" -ge "${limit_kib}" ]; then
    fail "snapshot gzipado em base64 (~${b64_kib} KiB) está acima do limite seguro (${limit_kib} KiB). Reduza ROUTES ou migre pra modo S3."
fi

step "3. Aplicando ConfigMap ${NAMESPACE}/${CONFIGMAP} em binaryData[${KEY}.gz] (server-side apply)"
# kubectl detecta conteúdo binário (não-UTF-8) e coloca em binaryData
# automaticamente quando o nome da entrada termina em .gz e os bytes não
# são válidos UTF-8.
kubectl create configmap "${CONFIGMAP}" -n "${NAMESPACE}" \
    --from-file="${KEY}.gz=/tmp/large-snapshot.yaml.gz" \
    --dry-run=client -o yaml >/tmp/configmap-large.yaml
mark_ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
kubectl apply --server-side --force-conflicts -f /tmp/configmap-large.yaml >/dev/null
echo "   ConfigMap aplicado em ${mark_ts}"

step "4. Coletando pods do gateway"
pods="$(kubectl get pods -n "${NAMESPACE}" \
    -l app.kubernetes.io/name="${GATEWAY_DEPLOYMENT}" \
    --field-selector=status.phase=Running \
    -o jsonpath='{range .items[?(@.status.containerStatuses[0].ready==true)]}{.metadata.name}{"\n"}{end}')"
[ -z "${pods}" ] && fail "nenhum pod do gateway Ready"
pod_count="$(wc -l <<<"${pods}" | tr -d ' ')"
echo "${pods}" | sed 's/^/   - /'

step "5. port-forward por pod"
mapping=""
i=0
while IFS= read -r pod; do
    [ -z "${pod}" ] && continue
    port=$((LOCAL_PORT_BASE + i))
    kubectl port-forward -n "${NAMESPACE}" "pod/${pod}" "${port}:8080" >/dev/null 2>&1 &
    pf_pids+=("$!")
    for _ in $(seq 1 50); do
        curl -fsS -o /dev/null "http://localhost:${port}/actuator/health" 2>/dev/null && break
        sleep 0.2
    done
    mapping+="${pod}:${port}"$'\n'
    echo "   ${pod} -> localhost:${port}"
    i=$((i + 1))
done <<<"${pods}"

step "6. Esperando todos os pods convergirem para ${EXPECTED_ROUTES} rotas (timeout ${WAIT_RELOAD_SECS}s)"
deadline=$((SECONDS + WAIT_RELOAD_SECS))
all_ready=0
while [ ${SECONDS} -lt ${deadline} ]; do
    all_ready=1
    while IFS=: read -r pod port; do
        [ -z "${pod}" ] && continue
        count=$(curl -fsS "http://localhost:${port}/internal/routes" 2>/dev/null \
            | python3 -c 'import json,sys
try:
    print(len(json.load(sys.stdin).get("routes", [])))
except Exception:
    print(0)' || echo 0)
        if [ "${count}" != "${EXPECTED_ROUTES}" ]; then
            all_ready=0
            break
        fi
    done <<<"${mapping}"
    [ ${all_ready} -eq 1 ] && break
    sleep 1
done
[ ${all_ready} -eq 1 ] || fail "timeout: nem todos os pods atingiram ${EXPECTED_ROUTES} rotas"
echo "   todos os ${pod_count} pods estão com ${EXPECTED_ROUTES} rotas em memória"

step "7. Tempo de reload por pod (extraído do log do gateway)"
while IFS=: read -r pod port; do
    [ -z "${pod}" ] && continue
    kubectl logs -n "${NAMESPACE}" "${pod}" --tail=20000 >/tmp/large-snapshot.log 2>/dev/null || true
    duration_info=$(EXPECTED_ROUTES="${EXPECTED_ROUTES}" python3 - /tmp/large-snapshot.log <<'PY'
import os, re, sys

# Última linha:
#   "Reload completed snapshotRoutes=N activeRoutes=M totalMs=T (loadMs=L mapMs=M convertMs=C replaceMs=R publishMs=P)"
# `snapshotRoutes` = quantas rotas vieram do ConfigMap (input);
# `activeRoutes`   = quantas viraram Route no locator depois de mapear+compilar.
# A diferença deveria sempre ser 0 — se for >0, é sinal de drop silencioso.
pattern = re.compile(
    r"Reload completed snapshotRoutes=(\d+) activeRoutes=(\d+) totalMs=(\d+) "
    r"\(loadMs=(\d+) mapMs=(\d+) convertMs=(\d+) replaceMs=(\d+) publishMs=(\d+)\)"
)
last = None
with open(sys.argv[1], "r", errors="replace") as fh:
    for line in fh:
        m = pattern.search(line)
        if m:
            last = m
if last is None:
    print("?  (no 'Reload completed' log line found)")
    sys.exit(0)
snap, active, total, load, mp, convert, replace, publish = (int(g) for g in last.groups())
expected = int(os.environ.get("EXPECTED_ROUTES", "0"))
match = "MATCH" if expected and active == expected else ("MISMATCH" if expected else "n/a")
print(
    f"total={total}ms (load={load}ms map={mp}ms convert={convert}ms replace={replace}ms publish={publish}ms) "
    f"snapshotRoutes={snap} activeRoutes={active} expected={expected} {match}"
)
PY
)
    echo "   ${pod}: ${duration_info}"
done <<<"${mapping}"

step "8. Latência de uma chamada simples por pod (3 medidas, /actuator/health)"
while IFS=: read -r pod port; do
    [ -z "${pod}" ] && continue
    samples=""
    for _ in 1 2 3; do
        t=$(curl -s -o /dev/null -w "%{time_total}" "http://localhost:${port}/actuator/health")
        samples="${samples} ${t}s"
    done
    echo "   ${pod}:${samples}"
done <<<"${mapping}"

echo
if [ "${INCLUDE_BASELINE}" = "1" ]; then
    echo "PASS: snapshot de ${TOTAL_STRESS_ROUTES} rotas de stress (${TEAMS}x${ROUTES}) + ${BASELINE_COUNT} baseline (${gz_kib} KiB gzipped, ~${b64_kib} KiB em base64) carregado em todos os ${pod_count} pods."
else
    echo "PASS: snapshot de ${TOTAL_STRESS_ROUTES} rotas (${TEAMS}x${ROUTES}) (${gz_kib} KiB gzipped, ~${b64_kib} KiB em base64) carregado em todos os ${pod_count} pods."
fi
echo "      o operator volta a ${ORIGINAL_OPERATOR_REPLICAS:-1} réplica no cleanup e republica o snapshot real."