#!/usr/bin/env bash
# Bootstrap manual da plataforma no cluster k3d, na ordem que `make deploy`
# não cobre quando você quer aplicar passo a passo. Útil pra debug, demo
# ao vivo ou cluster recém-criado em que faltou alguma peça.
#
# Cada bloco roda independente: o script NÃO aborta na primeira falha
# (set -e desligado) porque comandos como `kubectl get crd | grep saca`
# falham de propósito quando o CRD ainda não existe — é informativo.
# Erros reais (apply/rollout) ainda quebram porque chamamos `fail` neles.
#
# Uso:
#   scripts/bootstrap-platform.sh                   # ciclo completo
#   NAMESPACE=outro scripts/bootstrap-platform.sh   # override do namespace

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

NAMESPACE="${NAMESPACE:-platform}"
EXPECTED_CONTEXT="${EXPECTED_CONTEXT:-k3d-dynamic-gateway-lab}"

step() { echo; echo "==> $*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

# ----------------------------------------------------------------
# 1) Diagnóstico inicial
# ----------------------------------------------------------------
step "Contexto kubectl atual"
ctx=$(kubectl config current-context)
echo "  ${ctx}"
if [ "${ctx}" != "${EXPECTED_CONTEXT}" ]; then
    echo "  AVISO: contexto esperado '${EXPECTED_CONTEXT}' (defina EXPECTED_CONTEXT pra silenciar)"
fi

step "Namespaces existentes"
kubectl get ns

step "CRDs do projeto"
kubectl get crd | grep saca || echo "  Nenhum CRD do projeto ainda."

step "Recursos no namespace ${NAMESPACE}"
kubectl get all -n "${NAMESPACE}" || echo "  Namespace ${NAMESPACE} ainda não existe."

# ----------------------------------------------------------------
# 2) CRD GatewayRoute
# ----------------------------------------------------------------
step "Aplicando CRD GatewayRoute"
kubectl apply -f k8s/crd/gatewayroutes.platform.saca.pags.yaml \
    || fail "kubectl apply do CRD falhou"

step "Confirmando CRD instalado"
kubectl get crd gatewayroutes.platform.saca.pags
kubectl explain gatewayroute.spec | head -30

# ----------------------------------------------------------------
# 3) Operator (ServiceAccount + RBAC + Deployment)
#
# Sobe ANTES das GatewayRoutes pra ele pegar o evento de "added" via
# informer assim que o apply rolar — caso contrário rolaria reconcile
# só no próximo resync periódico.
# ----------------------------------------------------------------
step "Aplicando ServiceAccount do operator"
kubectl -n "${NAMESPACE}" apply -f k8s/operator/service-account.yaml \
    || fail "apply do ServiceAccount do operator falhou"

step "Aplicando RBAC do operator"
kubectl -n "${NAMESPACE}" apply -f k8s/operator/rbac.yaml \
    || fail "apply do RBAC do operator falhou"

step "Aplicando deployment do operator"
kubectl -n "${NAMESPACE}" apply -f k8s/operator/deployment.yaml \
    || fail "apply do deployment do operator falhou"

step "Aguardando rollout do gateway-route-operator"
kubectl rollout status deployment/gateway-route-operator -n "${NAMESPACE}" --timeout=120s \
    || fail "gateway-route-operator não ficou ready em 120s"

# ----------------------------------------------------------------
# 4) Sample GatewayRoutes (httpbin + anything)
#
# Aplica as duas rotas baseline que o cenário de carga
# (scripts/k6/run-load-scenario.sh) consome no pre-flight.
# ----------------------------------------------------------------
step "Aplicando sample route-httpbin"
kubectl -n "${NAMESPACE}" apply -f k8s/samples/route-httpbin.yaml \
    || fail "apply de route-httpbin falhou"

step "Aplicando sample route-anything"
kubectl -n "${NAMESPACE}" apply -f k8s/samples/route-anything.yaml \
    || fail "apply de route-anything falhou"

step "GatewayRoutes ativas em ${NAMESPACE}"
kubectl -n "${NAMESPACE}" get gatewayroutes

# ----------------------------------------------------------------
# 5) Backend de teste httpbin in-cluster
# ----------------------------------------------------------------
step "Aplicando deployment do go-httpbin (backend de teste)"
kubectl -n "${NAMESPACE}" apply -f k8s/samples/httpbin-in-cluster.yaml \
    || fail "apply do httpbin in-cluster falhou"

kubectl -n "${NAMESPACE}" rollout status deployment/go-httpbin --timeout=60s \
    || fail "go-httpbin não ficou ready em 60s"

step "Deployments e Services em ${NAMESPACE}"
kubectl -n "${NAMESPACE}" get deployment,service

# ----------------------------------------------------------------
# 6) Gateway propriamente dito (RBAC + Deployment + Service)
# ----------------------------------------------------------------
step "Aplicando RBAC do gateway"
kubectl -n "${NAMESPACE}" apply -f k8s/gateway/rbac.yaml \
    || fail "apply do RBAC do gateway falhou"

step "Aplicando deployment do gateway"
kubectl -n "${NAMESPACE}" apply -f k8s/gateway/deployment.yaml \
    || fail "apply do deployment do gateway falhou"

step "Aplicando service do gateway"
kubectl -n "${NAMESPACE}" apply -f k8s/gateway/service.yaml \
    || fail "apply do service do gateway falhou"

step "Aguardando rollout do dynamic-gateway"
kubectl rollout status deployment/dynamic-gateway -n "${NAMESPACE}" --timeout=180s \
    || fail "dynamic-gateway não ficou ready em 180s"

# ----------------------------------------------------------------
# 7) nginx-lb (entry point externo único)
#
# Por princípio do projeto, os pods do gateway NÃO ficam expostos
# diretamente — quem se conecta de fora (k3d Traefik, port-forward, k6)
# sempre passa pelo nginx-lb. Ele usa o headless service do gateway
# pra distribuir requests round-robin entre os pods.
# ----------------------------------------------------------------
step "Aplicando nginx-lb (entry point externo)"
kubectl -n "${NAMESPACE}" apply -f k8s/nginx-lb/ \
    || fail "apply do nginx-lb falhou"

step "Aguardando rollout do nginx-lb"
kubectl rollout status deployment/nginx-lb -n "${NAMESPACE}" --timeout=60s \
    || fail "nginx-lb não ficou ready em 60s"

# ----------------------------------------------------------------
# Resumo final
# ----------------------------------------------------------------
step "Estado final"
kubectl -n "${NAMESPACE}" get deployment,service,gatewayroutes
echo
echo "OK — plataforma pronta no namespace '${NAMESPACE}'."
echo "    Pra acessar de fora: \`make nginx-lb-pf\` e abrir http://localhost:18000/"
