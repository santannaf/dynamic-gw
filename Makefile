.PHONY: cluster down clean-build jars images load-images bootstrap deploy \
        standalone-up standalone-down nginx-lb-up nginx-lb-down nginx-lb-pf \
        test restart-test large-snapshot-test route-propagation-test \
        stress-test dump-snapshot clean

# ──────────────────────────────────────────────────────────────────────────
# Cluster e build
# ──────────────────────────────────────────────────────────────────────────

cluster:
	./scripts/create-cluster.sh

down:
	./scripts/delete-cluster.sh

clean-build:
	./gradlew clean build -x test

jars: clean-build
	./gradlew :gateway:bootJar :operator:bootJar -x test

images: jars
# 	docker buildx build --platform linux/arm64 -f gateway/Dockerfile -t dynamic-gateway:local --load .
	docker buildx build --platform linux/amd64 -f gateway/Dockerfile -t dynamic-gateway:local --load .
# 	docker buildx build --platform linux/arm64 -f operator/Dockerfile -t dynamic-gateway-operator:local --load .
	docker buildx build --platform linux/amd64 -f operator/Dockerfile -t dynamic-gateway-operator:local --load .

load-images: images
	k3d image import \
		dynamic-gateway:local \
		dynamic-gateway-operator:local \
		-c dynamic-gateway-lab && \
	docker exec k3d-dynamic-gateway-lab-server-0 \
		crictl pull mccutchen/go-httpbin:latest

# ──────────────────────────────────────────────────────────────────────────
# Deploy
# ──────────────────────────────────────────────────────────────────────────

# v2 step-by-step: CRD, operator, samples, gateway. Roteiro com diagnóstico
# em cada fase. Aplica também o nginx-lb porque ele é o entry point externo.
bootstrap: load-images
	./scripts/bootstrap-platform.sh

# v2 one-shot: build + import + apply de tudo via deploy-all.sh.
deploy:
	./scripts/deploy-all.sh

# v1 standalone: gateway com rotas em properties (sem operator/CRD/operator).
# Inclui o nginx-lb porque ele é o único ponto de exposição externo (princípio
# do projeto: pods do gateway nunca são expostos diretamente).
# Pré-requisito: `make load-images` já rodou uma vez.
standalone-up:
	kubectl apply -f k8s/namespace.yaml
	kubectl apply -f k8s/samples/httpbin-in-cluster.yaml
	kubectl -n platform rollout status deployment/go-httpbin --timeout=60s
	kubectl apply -f k8s/gateway-standalone/
	kubectl -n platform rollout status deployment/dynamic-gateway --timeout=180s
	kubectl apply -f k8s/nginx-lb/
	kubectl -n platform rollout status deployment/nginx-lb --timeout=60s
	@echo
	@echo "==> Standalone up. Pra testar via nginx-lb (entry point externo):"
	@echo "    make nginx-lb-pf            # port-forward foreground"
	@echo "    curl http://localhost:18000/internal/routes | python3 -m json.tool"
	@echo "    curl http://localhost:18000/httpbin/get"
	@echo "    curl http://localhost:18000/anything/get"

# Remove só o gateway standalone (deployment + service + ingress + nginx-lb).
# NÃO mexe no httpbin nem no namespace pra não atrapalhar outros cenários.
standalone-down:
	kubectl delete -f k8s/gateway-standalone/ --ignore-not-found
	kubectl delete -f k8s/nginx-lb/ --ignore-not-found

# ──────────────────────────────────────────────────────────────────────────
# Nginx LB — entry point externo único
# ──────────────────────────────────────────────────────────────────────────

# Aplica os manifests do nginx-lb (Deployment + Service + ConfigMap +
# headless service do gateway). Normalmente já vem aplicado pelo
# `standalone-up`/`bootstrap`; use só se você desfez por engano.
nginx-lb-up:
	kubectl apply -f k8s/nginx-lb/
	kubectl -n platform rollout status deployment/nginx-lb --timeout=60s

# Abre port-forward foreground pro nginx-lb. Garante (via nginx-lb-up) que
# os manifests estão aplicados antes — kubectl apply é idempotente, então
# não atrapalha se já estiver de pé. Ctrl+C derruba só o forward; manifests
# permanecem aplicados no cluster.
nginx-lb-pf: nginx-lb-up
	@echo "==> Port-forward svc/nginx-lb -> http://localhost:18000 (Ctrl+C para parar)"
	kubectl -n platform port-forward svc/nginx-lb 18000:80

# Remove o nginx-lb do cluster (e o port-forward se estiver rodando).
nginx-lb-down:
	-pkill -f 'kubectl.*port-forward.*svc/nginx-lb' || true
	kubectl delete -f k8s/nginx-lb/ --ignore-not-found

# ──────────────────────────────────────────────────────────────────────────
# Testes e debug
# ──────────────────────────────────────────────────────────────────────────

# Smoke test do ciclo CRUD via CRD (v2).
test:
	./scripts/test-routes.sh

# Valida restart-recovery: aplica rota, mata pod do gateway, confirma que
# o snapshot do ConfigMap repõe as rotas no pod novo.
restart-test:
	./scripts/restart-gateway-test.sh

# Push direto de N rotas no ConfigMap binaryData/gzip (60k default).
# Mede tempo de reload por pod.
large-snapshot-test:
	./scripts/test-large-snapshot.sh

# Mede tempo de propagação ponta-a-ponta: do `kubectl apply` de uma
# GatewayRoute até o primeiro 200 em cada pod do gateway. Roda isolado
# em cluster idle ou em paralelo com large-snapshot-test pra medir sob carga.
route-propagation-test:
	./scripts/test-route-propagation.sh

# Cenário de carga sustentada (20k req/min) durante múltiplos pushes
# pesados de 60k rotas. Default: 3 times × 20k rotas, 5min.
stress-test:
	TEAMS=3 ROUTES=20000 scripts/k6/run-load-scenario.sh

# Dump o ConfigMap consolidado pra inspeção manual.
dump-snapshot:
	./scripts/dump-snapshot.sh

# ──────────────────────────────────────────────────────────────────────────
# Clean
# ──────────────────────────────────────────────────────────────────────────

clean:
	./gradlew clean
