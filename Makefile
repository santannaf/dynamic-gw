.PHONY: cluster down clean-build jars images load-images bootstrap deploy \
        standalone-up standalone-down nginx-lb-up nginx-lb-down nginx-lb-pf \
        sso-up sso-down sso-test ldap-up ldap-test keycloak-reset keycloak-db-shell \
        oauth-up oauth-down oauth-test frontend-test \
        second-gateway-up second-gateway-down second-gateway-test \
        standalone-sso-up standalone-sso-down standalone-sso-test \
        s3-sso-up s3-sso-down s3-sso-test \
        test restart-test large-snapshot-test route-propagation-test \
        stress-test dump-snapshot policies-test clean

# ──────────────────────────────────────────────────────────────────────────
# Cluster e build
# ──────────────────────────────────────────────────────────────────────────

cluster:
	./scripts/cluster/create-cluster.sh

down:
	./scripts/cluster/delete-cluster.sh

clean-build:
	./gradlew clean build -x test

jars: clean-build
	./gradlew :gateway:bootJar :operator:bootJar -x test

images: jars
# 	docker buildx build --platform linux/arm64 -f gateway/Dockerfile -t dynamic-gateway:local --load .
	docker buildx build --pull --provenance=false --sbom=false --platform linux/amd64 -f gateway/Dockerfile -t dynamic-gateway:local --load .
# 	docker buildx build --platform linux/arm64 -f operator/Dockerfile -t dynamic-gateway-operator:local --load .
	docker buildx build --pull --provenance=false --sbom=false --platform linux/amd64 -f operator/Dockerfile -t dynamic-gateway-operator:local --load .

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
	./scripts/platform/bootstrap-platform.sh

# v2 one-shot: build + import + apply de tudo via deploy-all.sh.
deploy:
	./scripts/platform/deploy-all.sh

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
	./scripts/e2e/test-routes.sh

# Valida restart-recovery: aplica rota, mata pod do gateway, confirma que
# o snapshot do ConfigMap repõe as rotas no pod novo.
restart-test:
	./scripts/e2e/restart-gateway-test.sh

# Push direto de N rotas no ConfigMap binaryData/gzip (60k default).
# Mede tempo de reload por pod.
large-snapshot-test:
	./scripts/load/test-large-snapshot.sh

# Mede tempo de propagação ponta-a-ponta: do `kubectl apply` de uma
# GatewayRoute até o primeiro 200 em cada pod do gateway. Roda isolado
# em cluster idle ou em paralelo com large-snapshot-test pra medir sob carga.
route-propagation-test:
	./scripts/load/test-route-propagation.sh

# Cenário de carga sustentada (20k req/min) durante múltiplos pushes
# pesados de 60k rotas. Default: 3 times × 20k rotas, 5min.
stress-test:
	TEAMS=3 ROUTES=20000 scripts/load/k6/run-load-scenario.sh

# Dump o ConfigMap consolidado pra inspeção manual.
dump-snapshot:
	./scripts/platform/dump-snapshot.sh

# ──────────────────────────────────────────────────────────────────────────
# Clean
# ──────────────────────────────────────────────────────────────────────────

clean:
	./gradlew clean

# ──────────────────────────────────────────────────────────────────────────
# SSO + auditoria (openspec/changes/add-keycloak-sso-audit)
# ──────────────────────────────────────────────────────────────────────────
# Pré-requisito: plataforma v2 no ar (make deploy). HOST_PORT tem que ser a
# porta usada no `make cluster`: o issuer do Keycloak e o redirect do login
# dependem dela.

# Keycloak como ponte/IdP (provider=keycloak): OpenLDAP, banco do Keycloak,
# Keycloak e gateway. As peças ficam em scripts/ldap, scripts/db e scripts/keycloak.
# KEYCLOAK_DB=oracle troca o PostgreSQL pelo Oracle 26ai Free.
sso-up:
	PROVIDER=keycloak ./scripts/sso/deploy-sso.sh

# Gateway direto no diretório (provider=ldap, OpenLDAP no lugar do AD).
ldap-up:
	PROVIDER=ldap ./scripts/sso/deploy-sso.sh

# Volta o gateway sem SSO e remove Keycloak, banco e OpenLDAP (mantém os Secrets).
sso-down:
	./scripts/sso/deploy-sso.sh down

sso-test:
	./scripts/e2e/test-sso.sh

# Políticas de rota (rate limit, throttling, filtros, transformação, agregação):
# no v2 aplica k8s/samples/routes-policies.yaml; no v1 usa as rotas da imagem.
policies-test:
	./scripts/e2e/test-policies.sh

# Zera o banco do Keycloak: o realm volta a ser o do JSON e todas as sessões caem.
keycloak-reset:
	./scripts/keycloak/keycloak.sh reset

# Console do banco do Keycloak (psql ou sqlplus, conforme KEYCLOAK_DB).
keycloak-db-shell:
	./scripts/db/$${KEYCLOAK_DB:-postgres}.sh shell

ldap-test:
	./scripts/e2e/test-ldap.sh

# Canal de exemplo sem sessão (provider=oauth): API pública, só tokens. O Keycloak
# local faz o papel do authorization server.
oauth-up:
	PROVIDER=oauth ./scripts/sso/deploy-sso.sh

oauth-down:
	./scripts/sso/deploy-sso.sh down

oauth-test:
	./scripts/e2e/test-oauth.sh

# Modelos de integração de front-end (pré-requisito: make sso-up).
frontend-test:
	./scripts/e2e/test-frontend-models.sh

# Segundo gateway de borda no mesmo cluster, com namespace, operator e snapshot próprios.
second-gateway-up:
	kubectl kustomize --load-restrictor LoadRestrictionsNone k8s/second-gateway | kubectl apply -f -
	kubectl -n borda-exemplo rollout status deployment/dynamic-gateway --timeout=180s
	kubectl -n borda-exemplo rollout status deployment/gateway-route-operator --timeout=180s

second-gateway-down:
	kubectl delete namespace borda-exemplo --ignore-not-found

second-gateway-test:
	./scripts/e2e/test-second-gateway.sh

# v1 standalone com SSO (profile standalone-sso): rotas com `access` embarcadas
# na imagem, sem operator/CRD. PROVIDER=ldap troca o Keycloak pelo diretório.
standalone-sso-up:
	MODE=standalone PROVIDER=$${PROVIDER:-keycloak} ./scripts/sso/deploy-sso.sh

standalone-sso-down:
	MODE=standalone ./scripts/sso/deploy-sso.sh down

standalone-sso-test:
	./scripts/e2e/test-standalone-sso.sh

# Cenário S3 com SSO: CRD -> operator -> objeto S3. S3_BACKEND=minio (default,
# MinIO no cluster) ou S3_BACKEND=aws S3_BUCKET=... (Secret aws-credentials).
s3-sso-up:
	MODE=s3 PROVIDER=$${PROVIDER:-keycloak} ./scripts/sso/deploy-sso.sh

s3-sso-down:
	MODE=s3 ./scripts/sso/deploy-sso.sh down

s3-sso-test:
	@if [ "$${PROVIDER:-keycloak}" = "ldap" ]; then ./scripts/e2e/test-ldap.sh; else ./scripts/e2e/test-sso.sh; fi

