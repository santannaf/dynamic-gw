#!/usr/bin/env bash
# Orquestrador end-to-end do cenário "carga sustentada + reload pesado".
#
# Roda em paralelo:
#   - k6 batendo 20k req/min em /httpbin e /anything via nginx LB local
#   - Disparos agendados do `test-large-snapshot.sh` com INCLUDE_BASELINE=1
#     e ROUTES=30000, em t+60s, t+180s, t+270s (configurável)
#
# Ao final, parsea o sumário do k6 + os logs de cada push e cospe um
# relatório com veredito objetivo: o gateway aguentou ou não?
#
# Pré-requisitos (o script faz pre-flight check):
#   - cluster com operator + gateway de pé, baseline GatewayRoutes aplicadas
#   - nginx-lb instalado no namespace platform (já entra com `make bootstrap`
#     ou `make standalone-up`; se removeu, `make nginx-lb-up` recoloca)
#   - port-forward do nginx-lb ativo em localhost:18000:
#         make nginx-lb-pf
#
# Uso:
#   scripts/k6/run-load-scenario.sh
#
# Env vars (todas opcionais):
#   DURATION       Default 5m   (passada pro k6)
#   ROUTES         Default 30000  (rotas extras de stress por push)
#   SCHEDULE       Default "60 180 270"  (offsets em segundos dos pushes)
#   BASE_URL       Default http://localhost:18000  (LB nginx)
#   WORK_DIR       Default /tmp/dyngw-load-scenario  (logs + JSON + relatório)
#   K6_SCRIPT      Default scripts/k6/load-during-large-snapshot.js
#   PUSH_SCRIPT    Default scripts/test-large-snapshot.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT}"

DURATION="${DURATION:-5m}"
ROUTES="${ROUTES:-30000}"
TEAMS="${TEAMS:-1}"
SCHEDULE="${SCHEDULE:-60 180 270}"
BASE_URL="${BASE_URL:-http://localhost:18000}"
WORK_DIR="${WORK_DIR:-/tmp/dyngw-load-scenario}"
K6_SCRIPT="${K6_SCRIPT:-scripts/k6/load-during-large-snapshot.js}"
PUSH_SCRIPT="${PUSH_SCRIPT:-scripts/test-large-snapshot.sh}"

mkdir -p "${WORK_DIR}"
K6_LOG="${WORK_DIR}/k6.log"
K6_SUMMARY="${WORK_DIR}/k6-summary.json"
PUSH_RESULTS="${WORK_DIR}/pushes.tsv"
REPORT="${WORK_DIR}/report.md"

# limpar artefatos antigos pra não confundir o relatório
: > "${PUSH_RESULTS}"
rm -f "${K6_SUMMARY}" "${REPORT}" "${WORK_DIR}"/push-*.log

step() { echo; echo "==> $*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

# ----------------------------------------------------------------
# Pre-flight
# ----------------------------------------------------------------
step "Pre-flight"

command -v k6 >/dev/null      || fail "k6 não encontrado no PATH"
command -v jq >/dev/null      || JQ_MISSING=1   # opcional, vamos cair pra python
command -v python3 >/dev/null || fail "python3 não encontrado"
command -v kubectl >/dev/null || fail "kubectl não encontrado"

# nginx-lb dentro do cluster (Deployment+Service em k8s/nginx-lb/).
# O port-forward pra svc/nginx-lb cai em /lb/health => 200.
if ! curl -fsS "${BASE_URL}/lb/health" >/dev/null 2>&1; then
    fail "nginx LB em ${BASE_URL}/lb/health não responde – rode 'make nginx-lb-up' + 'make nginx-lb-pf'"
fi
echo "  LB ${BASE_URL}/lb/health OK"

# Baseline tem que estar publicada (senão setup do k6 aborta lá na frente)
for path in "/httpbin/get" "/anything/load-test"; do
    code=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}${path}")
    if [ "${code}" != "200" ]; then
        fail "${BASE_URL}${path} respondeu HTTP ${code} – confira GatewayRoutes baseline"
    fi
    echo "  ${path} -> HTTP 200"
done

# Cluster OK
kubectl get deployment/dynamic-gateway -n platform >/dev/null \
    || fail "deployment dynamic-gateway/platform inacessível"
kubectl get deployment/gateway-route-operator -n platform >/dev/null \
    || fail "deployment gateway-route-operator/platform inacessível"
echo "  cluster OK"

# Estado do cluster: o snapshot ATUAL precisa estar pequeno (baseline ~2
# rotas). Se já estiver em 30k+, é sobra de uma execução anterior – os
# pushes seriam no-op (test-large-snapshot.sh confere "todos os pods têm
# EXPECTED_ROUTES rotas" e essa checagem passaria de cara, terminando em
# 3-5s sem fazer trabalho real). Melhor abortar agora com mensagem clara.
route_count=$(curl -fsS "${BASE_URL}/internal/routes" \
    | python3 -c 'import json,sys; print(len(json.load(sys.stdin).get("routes", [])))' 2>/dev/null || echo "?")
echo "  rotas atuais no gateway: ${route_count}"
if [ "${route_count}" = "?" ]; then
    fail "não consegui ler /internal/routes via LB"
fi
if [ "${route_count}" -gt 10 ]; then
    cat >&2 <<EOF
FAIL: cluster tem ${route_count} rotas – parece sobra de uma execução anterior
de \`make large-snapshot-test\`. Os pushes do orquestrador virariam no-op porque
test-large-snapshot.sh terminaria de cara achando que já está convergido.

Pra resetar: confira se o operator está em 1 réplica e que ele republicou o
snapshot real (deveria ter só ~2 rotas baseline):

  kubectl scale deployment/gateway-route-operator -n platform --replicas=1
  sleep 5
  curl -s ${BASE_URL}/internal/routes \\
      | python3 -c 'import json,sys; print("count:", len(json.load(sys.stdin)["routes"]))'

Quando voltar pra ~2, rode esse script de novo.
EOF
    exit 1
fi

[ -x "${PUSH_SCRIPT}" ] || fail "${PUSH_SCRIPT} não é executável"
[ -f "${K6_SCRIPT}" ]   || fail "${K6_SCRIPT} não encontrado"

# ----------------------------------------------------------------
# Subir k6 em background
# ----------------------------------------------------------------
step "Iniciando k6 em background (DURATION=${DURATION})"
START_TS=$(date +%s)

DURATION="${DURATION}" BASE_URL="${BASE_URL}" \
    k6 run --summary-export="${K6_SUMMARY}" "${K6_SCRIPT}" > "${K6_LOG}" 2>&1 &
K6_PID=$!
echo "  k6 PID=${K6_PID}, log=${K6_LOG}"

# Cleanup: se Ctrl+C, derruba k6 e qualquer push em curso
cleanup() {
    if kill -0 "${K6_PID}" 2>/dev/null; then
        echo
        echo "==> Interrompido – matando k6 (PID ${K6_PID})"
        kill "${K6_PID}" 2>/dev/null || true
    fi
}
trap cleanup EXIT INT TERM

# Dá uns segundos pro k6 fazer setup() antes do primeiro push
sleep 2
if ! kill -0 "${K6_PID}" 2>/dev/null; then
    echo
    echo "k6 morreu durante setup – log:"
    tail -40 "${K6_LOG}"
    exit 1
fi

# ----------------------------------------------------------------
# Loop dos pushes (sequencial – pushes não podem rodar concorrentes pois
# test-large-snapshot.sh pausa e religa o operator)
# ----------------------------------------------------------------
step "Agendando pushes nos offsets: ${SCHEDULE}"
(
    n=0
    for offset in ${SCHEDULE}; do
        n=$((n + 1))
        target_ts=$((START_TS + offset))

        # Aguarda chegar no horário (ou imediato se previous push atrasou)
        while [ "$(date +%s)" -lt "${target_ts}" ]; do
            sleep 1
            kill -0 "${K6_PID}" 2>/dev/null || { echo "[push #${n}] k6 morreu, abortando agenda" >&2; exit 0; }
        done

        push_log="${WORK_DIR}/push-${n}.log"
        push_start=$(date +%s)
        push_start_offset=$((push_start - START_TS))

        echo "[t+${push_start_offset}s] push #${n} iniciando -> ${push_log}"
        if INCLUDE_BASELINE=1 ROUTES="${ROUTES}" TEAMS="${TEAMS}" \
            "${PUSH_SCRIPT}" >"${push_log}" 2>&1; then
            status="OK"
        else
            status="FAIL"
        fi
        push_end=$(date +%s)
        dur=$((push_end - push_start))
        push_end_offset=$((push_end - START_TS))

        printf "%d\t%d\t%d\t%d\t%s\t%s\n" \
            "${n}" "${push_start_offset}" "${push_end_offset}" "${dur}" "${status}" "${push_log}" \
            >> "${PUSH_RESULTS}"

        echo "[t+${push_end_offset}s] push #${n} terminou (${status}, ${dur}s)"
    done
) &
ORCH_PID=$!

# ----------------------------------------------------------------
# Espera k6 terminar
# ----------------------------------------------------------------
step "Aguardando k6 terminar"
set +e
wait "${K6_PID}"
K6_EXIT=$?
set -e
echo "  k6 terminou com exit code ${K6_EXIT}"

# Se o último push ainda está rodando, espera ele terminar (deixar artefato
# pendurado no cluster é pior do que esperar 30s extras).
if kill -0 "${ORCH_PID}" 2>/dev/null; then
    echo "  aguardando push em curso terminar..."
    wait "${ORCH_PID}" 2>/dev/null || true
fi
trap - EXIT INT TERM

# ----------------------------------------------------------------
# Relatório
# ----------------------------------------------------------------
step "Gerando relatório -> ${REPORT}"

python3 - "${K6_SUMMARY}" "${PUSH_RESULTS}" "${K6_EXIT}" "${DURATION}" "${ROUTES}" "${REPORT}" "${TEAMS}" <<'PY'
import json, sys, os

summary_path, pushes_path, k6_exit, duration, routes, report_path, teams = sys.argv[1:]
teams_n = int(teams)
routes_per_team = int(routes)
total_stress = teams_n * routes_per_team

# --- k6 summary -----------------------------------------------------------
try:
    with open(summary_path) as fh:
        s = json.load(fh)
    metrics = s.get("metrics", {})
except FileNotFoundError:
    print(f"AVISO: k6 não produziu sumário em {summary_path}", file=sys.stderr)
    metrics = {}

def val(name, key, default=0):
    # k6 v1.x summary-export: campos da métrica direto no objeto, sem wrapper "values".
    # Ex.: metrics.http_reqs.count, metrics.http_req_duration["p(95)"], etc.
    m = metrics.get(name, {})
    if isinstance(m, dict) and "values" in m and key in m["values"]:
        # Fallback p/ versões antigas do k6 que ainda usavam "values".
        return m["values"].get(key, default)
    return m.get(key, default)

http_reqs    = val("http_reqs", "count")
fail_rate    = val("http_req_failed", "rate")
fail_count   = val("http_req_failed", "passes")   # k6 conta diferente; melhor calcular
# rate total: failed_count = round(rate * http_reqs)
failed_total = round(fail_rate * http_reqs) if http_reqs else 0
gw_5xx       = val("gateway_5xx", "count")
gw_4xx       = val("gateway_4xx", "count")
gw_conn      = val("gateway_connection_errors", "count")
lat_avg      = val("http_req_duration", "avg")
lat_p50      = val("http_req_duration", "med")
lat_p95      = val("http_req_duration", "p(95)")
lat_p99      = val("http_req_duration", "p(99)")
lat_max      = val("http_req_duration", "max")
dropped      = val("dropped_iterations", "count")

def per_route(name, route):
    sub = metrics.get(f"{name}{{route:{route}}}", {})
    # mesmo tratamento da função val(): flat OU values.
    if isinstance(sub, dict) and "values" in sub:
        return sub["values"]
    return sub

# --- Pushes ---------------------------------------------------------------
pushes = []
try:
    with open(pushes_path) as fh:
        for line in fh:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 6:
                pushes.append({
                    "n": int(parts[0]),
                    "start_offset_s": int(parts[1]),
                    "end_offset_s": int(parts[2]),
                    "duration_s": int(parts[3]),
                    "status": parts[4],
                    "log": parts[5],
                })
except FileNotFoundError:
    pass

# Tempo de reload + verificação de count extraídos do log de cada push.
# test-large-snapshot.sh parseia os logs do gateway (novo formato:
# "Reload completed snapshotRoutes=N activeRoutes=M ...") e cospe uma linha
# por pod no formato:
#   "<pod>: total=1416ms (load=775ms map=95ms convert=525ms replace=0ms publish=21ms) snapshotRoutes=N activeRoutes=M expected=K MATCH"
# Capturamos snapshotRoutes (o que veio do ConfigMap), activeRoutes (o que
# foi compilado e ficou ativo no locator) e expected (TOTAL_STRESS+baseline,
# já calculado pelo test-large-snapshot.sh). Se algum pod não casar com
# `expected`, derrubamos o veredito de "rotas chegaram em todos os pods".
import re
reload_re = re.compile(
    r"^\s*(\S+):\s*total=(\d+)ms\s*"
    r"\(load=(\d+)ms\s+map=(\d+)ms\s+convert=(\d+)ms\s+replace=(\d+)ms\s+publish=(\d+)ms\)"
    r"\s+snapshotRoutes=(\d+)\s+activeRoutes=(\d+)\s+expected=(\d+)"
)
for p in pushes:
    reloads = []
    if os.path.exists(p["log"]):
        with open(p["log"], errors="replace") as fh:
            for line in fh:
                m = reload_re.search(line)
                if m:
                    snapshot_routes = int(m.group(8))
                    active_routes = int(m.group(9))
                    expected = int(m.group(10))
                    reloads.append({
                        "pod": m.group(1),
                        "total_ms": int(m.group(2)),
                        "load_ms": int(m.group(3)),
                        "map_ms": int(m.group(4)),
                        "convert_ms": int(m.group(5)),
                        "replace_ms": int(m.group(6)),
                        "publish_ms": int(m.group(7)),
                        "snapshot_routes": snapshot_routes,
                        "active_routes": active_routes,
                        "expected_routes": expected,
                        "match": expected > 0 and active_routes == expected,
                    })
    p["reloads"] = reloads

# --- Veredito -------------------------------------------------------------
verdicts = []
def verdict(ok, ok_msg, bad_msg):
    verdicts.append(("✅" if ok else "❌", ok_msg if ok else bad_msg))

verdict(gw_5xx == 0,
        f"gateway_5xx=0 – nenhum erro de servidor durante os {len(pushes)} push(es)",
        f"gateway_5xx={int(gw_5xx)} – gateway retornou erro de servidor")

verdict(gw_conn == 0,
        "gateway_connection_errors=0 – pods sempre responderam",
        f"gateway_connection_errors={int(gw_conn)} – houve falha de conexão")

verdict(gw_4xx == 0,
        "gateway_4xx=0 – baseline (/httpbin, /anything) preservada em todos os reloads",
        f"gateway_4xx={int(gw_4xx)} – baseline ficou indisponível em algum momento")

verdict(lat_p95 < 500,
        f"latência p(95)={lat_p95:.1f}ms < 500ms",
        f"latência p(95)={lat_p95:.1f}ms acima de 500ms")

ok_pushes = sum(1 for p in pushes if p["status"] == "OK")
verdict(ok_pushes == len(pushes) and len(pushes) > 0,
        f"{ok_pushes}/{len(pushes)} pushes completaram com sucesso",
        f"apenas {ok_pushes}/{len(pushes)} pushes completaram")

# Push só é suspeito se NÃO houver evidência de reload no log. Tempo
# absoluto não conta porque o gateway pode reload 30k rotas em < 2s e o
# script inteiro terminar em 4-5s legitimamente.
suspicious = [p for p in pushes if not p["reloads"]]
verdict(len(suspicious) == 0,
        "todos os pushes registraram reload do gateway nos logs",
        f"{len(suspicious)} push(es) sem evidência de reload: #" + ", #".join(str(p["n"]) for p in suspicious))

# Casamento de count: para cada push, todos os pods reportaram activeRoutes
# == expected? expected vem do test-large-snapshot.sh (TOTAL_STRESS + baseline).
# Se algum pod ficou abaixo, é evidência de propagação parcial ou drop silencioso.
all_reloads = [r for p in pushes for r in p["reloads"]]
mismatch = [r for r in all_reloads if not r["match"]]
verdict(all_reloads and not mismatch,
        f"todos os {len(all_reloads)} reload(s) bateram com expected " +
        f"(activeRoutes == {all_reloads[0]['expected_routes'] if all_reloads else 0})",
        f"{len(mismatch)}/{len(all_reloads)} reload(s) com count divergente: " +
        ", ".join(f"{r['pod']}(active={r['active_routes']} vs expected={r['expected_routes']})" for r in mismatch[:5]))

verdict(k6_exit in ("0",),
        f"k6 finalizou clean (exit {k6_exit})",
        f"k6 finalizou com exit {k6_exit} – algum threshold quebrou")

overall = all(v[0] == "✅" for v in verdicts)

# --- Render ---------------------------------------------------------------
lines = []
lines.append("# Relatório – carga sustentada + reload pesado")
lines.append("")
lines.append(f"- duração k6:      {duration}")
if teams_n == 1:
    lines.append(f"- rotas por push:  {routes_per_team:,} stress + 2 baseline")
else:
    lines.append(f"- rotas por push:  {total_stress:,} stress ({teams_n} times x {routes_per_team:,}) + 2 baseline")
lines.append(f"- pushes agendados: {len(pushes)}")
lines.append(f"- k6 exit code:    {k6_exit}")
lines.append("")
lines.append("## Tráfego (k6)")
lines.append("")
lines.append(f"- requests totais:          {int(http_reqs):,}")
lines.append(f"- requests com falha:       {failed_total:,} ({fail_rate*100:.2f}%)")
lines.append(f"  - gateway_5xx:            {int(gw_5xx)}")
lines.append(f"  - gateway_4xx:            {int(gw_4xx)}")
lines.append(f"  - connection errors:      {int(gw_conn)}")
lines.append(f"- iterations dropped (k6):  {int(dropped)}")
lines.append("")
lines.append("### Latência")
lines.append("")
lines.append(f"- avg:  {lat_avg:.2f} ms")
lines.append(f"- p50:  {lat_p50:.2f} ms")
lines.append(f"- p95:  {lat_p95:.2f} ms")
lines.append(f"- p99:  {lat_p99:.2f} ms")
lines.append(f"- max:  {lat_max:.2f} ms")
lines.append("")

# Por rota
lines.append("### Por rota")
lines.append("")
lines.append("| rota     | falha % | p(95) ms |")
lines.append("|----------|---------|----------|")
for r in ("httpbin", "anything"):
    fr = per_route("http_req_failed", r).get("rate", 0)
    p95 = per_route("http_req_duration", r).get("p(95)", 0)
    lines.append(f"| {r:<9} | {fr*100:>7.2f}% | {p95:>8.1f} |")
lines.append("")

# Pushes
lines.append("## Pushes de snapshot grande")
lines.append("")
if teams_n == 1:
    lines.append(f"Cada push aplica {routes_per_team:,} rotas de stress + 2 baseline (httpbin, anything) via")
else:
    lines.append(f"Cada push aplica {total_stress:,} rotas de stress ({teams_n} times disjuntos com {routes_per_team:,} rotas cada,")
    lines.append("prefixos `team-1-*`, `team-2-*`, ...) + 2 baseline (httpbin, anything) via")
lines.append("ConfigMap binaryData/gzip. As colunas `reload por pod` mostram o tempo medido")
lines.append("pelo próprio gateway (total = load + map + convert + replace + publish).")
lines.append("")
lines.append("| # | start (t+) | end (t+) | duração total | status | reload por pod |")
lines.append("|---|------------:|---------:|--------------:|--------|----------------|")
for p in pushes:
    if p["reloads"]:
        reload_str = "<br>".join(
            f"`{r['pod']}` total={r['total_ms']}ms "
            f"(load={r['load_ms']} map={r['map_ms']} convert={r['convert_ms']} "
            f"replace={r['replace_ms']} publish={r['publish_ms']}) "
            f"snapshot={r['snapshot_routes']} active={r['active_routes']} "
            f"expected={r['expected_routes']} "
            f"{'✅' if r['match'] else '❌'}"
            for r in p["reloads"]
        )
    else:
        reload_str = "_sem evidência de reload no log_"
    lines.append(
        f"| {p['n']} | {p['start_offset_s']:>9}s | {p['end_offset_s']:>7}s "
        f"| {p['duration_s']:>11}s | {p['status']:<6} | {reload_str} |"
    )
lines.append("")

# Veredito
lines.append("## Veredito")
lines.append("")
for icon, msg in verdicts:
    lines.append(f"- {icon} {msg}")
lines.append("")
lines.append("## Conclusão")
lines.append("")
if overall:
    lines.append("**✅ PASS** – gateway absorveu carga sustentada de 20k req/min nas rotas")
    lines.append("baseline enquanto recebeu múltiplos reloads de snapshot grande sem")
    lines.append("apresentar erros de servidor, indisponibilidade de rota ou degradação")
    lines.append("relevante de latência. Downstream (`go-httpbin`) permaneceu acessível.")
else:
    lines.append("**❌ FAIL** – algum critério não foi atendido. Veja o detalhamento acima")
    lines.append("e investigue os logs:")
    lines.append("")
    lines.append(f"- k6 stdout: `{os.path.relpath(summary_path)}` e log adjacente")
    for p in pushes:
        lines.append(f"- push #{p['n']}: `{p['log']}`")
lines.append("")

text = "\n".join(lines)
with open(report_path, "w") as fh:
    fh.write(text)
print(text)
PY

echo
echo "==> Relatório salvo em ${REPORT}"
echo "==> Logs por push em ${WORK_DIR}/push-*.log, log do k6 em ${K6_LOG}"