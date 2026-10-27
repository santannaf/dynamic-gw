# dynamic-gateway-control-plane

POC de um **control plane de gateway dinâmico nativo de Kubernetes**: rotas HTTP são declaradas via CRD `GatewayRoute`, um operator consolida-as num snapshot canônico, e uma instância de **Spring Cloud Gateway** materializa esse snapshot na memória — refletindo mudanças em poucos segundos, **sem restart do pod**, e **recuperando todas as rotas após restart** a partir do snapshot persistido.

O backend de persistência neste POC é um `ConfigMap` do Kubernetes (com gzip + `binaryData` pra caber catálogos grandes). A arquitetura mantém o backend atrás de uma abstração fina, então trocar pra S3 não toca no reconciler do operator nem no pipeline de reload do gateway. Existe ainda um modo **standalone** (v1) sem operator/CRD para entregas iniciais em ambientes que ainda não têm essa infraestrutura.

---

## Sumário

1. [Modos de entrega: v1 standalone vs v2 com operator](#modos-de-entrega-v1-standalone-vs-v2-com-operator)
2. [Arquitetura](#arquitetura)
3. [Layout dos módulos](#layout-dos-módulos)
4. [Pré-requisitos](#pré-requisitos)
5. [Quick start](#quick-start)
6. [Trabalhando com rotas (v2)](#trabalhando-com-rotas-v2)
7. [Recuperação após restart](#recuperação-após-restart)
8. [Modo standalone (v1)](#modo-standalone-v1)
9. [Logs e observabilidade](#logs-e-observabilidade)
10. [Stress tests](#stress-tests)
11. [Backend de armazenamento](#backend-de-armazenamento)
12. [Operator por dentro](#operator-por-dentro)
13. [Otimização: detectar snapshots iguais sem ocupar memória](#otimização-detectar-snapshots-iguais-sem-ocupar-memória)
14. [Otimização: 60 mil rotas num `ConfigMap` de 1 MiB](#otimização-60-mil-rotas-num-configmap-de-1-mib)
15. [SSO, autorização por rota e auditoria](#sso-autorização-por-rota-e-auditoria)
16. [Limitações conhecidas](#limitações-conhecidas)
17. [Próximos passos](#próximos-passos)
18. [Licença](#licença)

---

## Modos de entrega: v1 standalone vs v2 com operator

Duas formas de rodar o gateway, decididas por uma única propriedade (`gateway.routes.store.type`):

| Característica                      | **v1 standalone**                            | **v2 com operator**                                                  |
|-------------------------------------|----------------------------------------------|----------------------------------------------------------------------|
| Rotas declaradas em                 | `application.yaml` do gateway                | CRDs `GatewayRoute` no cluster                                       |
| CRD instalado                       | ❌                                           | ✅                                                                   |
| Operator deployado                  | ❌                                           | ✅                                                                   |
| RBAC pra `ConfigMap`-watch          | ❌ não precisa                               | ✅                                                                   |
| Trocar rota sem restart             | ❌ exige rollout                             | ✅ ~250 ms via watcher                                               |
| Multi-time (cada um aplicando CRDs) | ❌                                           | ✅                                                                   |
| Manifests deployados                | `k8s/gateway-standalone/`                    | `k8s/namespace.yaml` + `k8s/crd/` + `k8s/operator/` + `k8s/gateway/` |
| Guia detalhado                      | [`TESTE_STANDALONE.md`](TESTE_STANDALONE.md) | [`TESTE_CONFIGMAP.md`](TESTE_CONFIGMAP.md)                           |
| Hot-reload via S3                   | –                                            | [`TESTE_S3.md`](TESTE_S3.md), [`SSO_CENARIO_S3.md`](SSO_CENARIO_S3.md) |

Os dois modos compartilham 100% do pipeline downstream (`RouteConfigEntry` → `RouteDefinitionMapper` → `FastRouteCompiler` → `InMemoryDynamicRouteLocator`). Só a fonte do snapshot muda — `PropertiesRouteConfigProvider` vs `ConfigMapRouteConfigProvider` vs `S3RouteConfigProvider`.

---

## Arquitetura

O diagrama abaixo descreve o fluxo da **v2** (com operator + CRD). Na v1, salta direto do `application.yaml` empacotado para o `PropertiesRouteConfigProvider` — o resto do pipeline é idêntico.

```
Platform user
   |
   v  kubectl apply GatewayRoute (CRD)
Kubernetes API
   |
   v  ADDED / MODIFIED / DELETED informer events
gateway-route-operator (Fabric8 informer + debounced reconciler)
   |
   v  RouteConfigPublisher.publish(snapshot)
ConfigMap  platform/gateway-routes  (binaryData.routes.yaml.gz, ~20× compressão)
   |
   v  POST /internal/routes/reload   (best-effort signal)
dynamic-gateway (Spring Cloud Gateway, reactive, native image)
   |
   v  RouteConfigProvider.load() -> RouteDefinitionMapper -> InMemoryDynamicRouteLocator.replaceAll(...)
   |
   v  RefreshRoutesEvent
In-memory routes, ready to serve traffic
```

Diagrama interativo (Excalidraw): https://excalidraw.com/#json=6kDnSH7P21tm-Wb_Tt0zS,xCs6nkUE7mbTF8o4Tp2m1g

Três responsabilidades explicitamente separadas:

| Responsabilidade              | Implementação                                                              |
|-------------------------------|----------------------------------------------------------------------------|
| Fonte da verdade declarativa  | CRDs `GatewayRoute` no namespace `platform` (v2) / `application.yaml` (v1) |
| Estado materializado (hoje)   | YAML gzipado em `ConfigMap platform/gateway-routes` (v2) / properties (v1) |
| Estado materializado (futuro) | YAML em S3 — mesma interface, plug-in (`S3RouteConfigProvider`)            |
| Estado em runtime             | Mapa de `Route`s na memória do pod do gateway                              |

O sinal HTTP (`POST /internal/routes/reload`) é **best-effort**. Ele **não é** a fonte da verdade — o bootstrap runner do gateway lê o snapshot mais recente no startup do pod, então um sinal perdido se cura no próximo restart ou no próximo reconcile bem-sucedido.

---

## Layout dos módulos

```
shared/    # modelo storage-agnostic: RouteConfigSnapshot, RouteConfigEntry, SnapshotCodec
gateway/   # Spring Cloud Gateway + providers (ConfigMap / S3 / Properties)
operator/  # Fabric8 informer + reconciler + publisher (ConfigMap / S3)
k8s/       # namespace, CRD, RBAC, Deployments, Services, Ingress, samples
scripts/   # cluster + bootstrap + deploy + carga + propagação + memória
openspec/  # propostas de mudança e specs por capability
```

O módulo `shared` propositadamente **não depende** de nada Kubernetes- ou AWS-específico — essa invariante é amarrada por testes (`StorageAbstractionLeakTest`) em `gateway` e `operator`.

---

## Pré-requisitos

- JDK 25 (testado com GraalVM Community 25)
- Docker
- `k3d` (cluster local de Kubernetes)
- `kubectl`
- Porta `8080` livre no host (e `18000` se for rodar o cenário de carga)
- Opcional: `make` (o `Makefile` é wrapper fino sobre os scripts)
- Opcional: `k6` (cenário de carga sustentada)
- Opcional: `metrics-server` instalado no cluster (`scripts/measure-gateway-memory.sh` depende disso)

---

## Quick start

> **Princípio do projeto:** os pods do gateway **não são expostos diretamente**. Quem vem de fora (k3d Traefik, port-forward de teste, k6) sempre passa pelo **`nginx-lb`** — um Deployment dentro do cluster que faz round-robin entre as réplicas do gateway via headless service. Tanto `standalone-up` quanto `bootstrap` já aplicam o nginx-lb por default; o Ingress aponta pra ele, não pro service do gateway.

**v1 standalone** (mais simples — sem operator, rotas no `application.yaml`):

```bash
make cluster          # k3d cria o cluster
make load-images      # build dos jars + imagens nativas + import no cluster
make standalone-up    # aplica httpbin, gateway-standalone e nginx-lb

# Em outro terminal: port-forward pro entry point único (nginx-lb)
make nginx-lb-pf

# E no terminal original:
curl -s http://localhost:18000/internal/routes | python3 -m json.tool
curl -s http://localhost:18000/httpbin/get
```

Detalhamento + troubleshooting em [`TESTE_STANDALONE.md`](TESTE_STANDALONE.md).

**v2 com operator** (CRD + ConfigMap dinâmico):

```bash
make cluster
make load-images
make bootstrap        # CRD, operator, samples, gateway e nginx-lb na ordem certa

kubectl apply -f k8s/samples/route-httpbin.yaml
make nginx-lb-pf      # outro terminal
curl http://localhost:18000/httpbin/get
```

Detalhamento em [`TESTE_CONFIGMAP.md`](TESTE_CONFIGMAP.md). O `bootstrap-platform.sh` é o roteiro passo-a-passo com diagnóstico.

---

## Trabalhando com rotas (v2)

### Criar

```bash
kubectl apply -f k8s/samples/route-httpbin.yaml
kubectl get gatewayroutes -n platform
```

### Inspecionar o que o gateway tem em memória

```bash
curl http://localhost:8080/internal/routes | python3 -m json.tool
```

Cada elemento traz: `id`, `uri`, `predicates`, `filters`, e — quando definidos no CRD — `team` e `description`.

### Atualizar

Edita o YAML ou usa `kubectl patch`, depois reaplica. O operator coalesce uma rajada de eventos numa janela curta de debounce (default 200 ms).

```bash
kubectl patch gatewayroute httpbin-route -n platform \
    --type=merge -p '{"spec":{"stripPrefix":2}}'
```

### Deletar

```bash
kubectl delete -f k8s/samples/route-httpbin.yaml
```

Em segundos, `GET /internal/routes` deixa de listar `httpbin-route` e `curl /httpbin/get` passa a retornar 404.

### Disabled e invalid

- `k8s/samples/route-disabled.yaml` tem `enabled: false`. Operator dropa do snapshot publicado. O CRD continua existindo; nada quebra se reativar depois.
- `k8s/samples/route-invalid.yaml` tem `targetUri` só com whitespace. Operator loga warning de validação e pula; rotas vizinhas não são afetadas.

### `team` e `description` (metadados opcionais)

```yaml
spec:
  path: /httpbin/**
  targetUri: http://go-httpbin.platform.svc.cluster.local:8080
  stripPrefix: 1
  methods: [GET, POST]
  enabled: true
  team: platform-team                                       # opcional
  description: Smoke test echoing requests via httpbin.    # opcional
```

Não afetam matching; viajam dentro do snapshot e são expostos no JSON do `/internal/routes`. Visíveis em `kubectl get gwr` (coluna `Team`).

---

## Recuperação após restart

```bash
kubectl apply -f k8s/samples/route-httpbin.yaml
curl http://localhost:8080/httpbin/get                                  # OK

kubectl rollout restart deployment/dynamic-gateway -n platform
kubectl rollout status  deployment/dynamic-gateway -n platform

curl http://localhost:8080/httpbin/get                                  # ainda OK
curl http://localhost:8080/internal/routes                              # lista httpbin-route
```

`./scripts/restart-gateway-test.sh` automatiza essa sequência. O gateway recupera do **`ConfigMap`**, não de um evento em memória — por isso sobrevive a restart de pod mesmo quando o pod do operator também está sendo reciclado.

---

## Modo standalone (v1)

A v1 dispensa CRD, operator e ConfigMap-watcher. Rotas vivem no [`application-standalone.yaml`](gateway/src/main/resources/application-standalone.yaml) embarcado na imagem do gateway. Trocar rota = rebuild da imagem + rollout restart do pod (sem hot-reload, por design).

```bash
make load-images
make standalone-up    # gateway + nginx-lb

make nginx-lb-pf      # em outro terminal — entry point único
curl http://localhost:18000/internal/routes | python3 -m json.tool
```

O `Deployment` ativa o profile Spring `standalone` (`SPRING_PROFILES_ACTIVE=standalone`), que liga `gateway.routes.store.type=properties`.

**O que NÃO funciona em v1:**

- `kubectl apply` de um CRD `GatewayRoute` — sem CRD instalado, vira erro `the server doesn't have a resource type "gatewayroute"`.
- `POST /internal/routes/reload` causa um reload mas o snapshot que ele lê é o mesmo cacheado em memória — efetivamente no-op. Aparece no log como `Snapshot routes unchanged ... skipping reload`.
- Adicionar/remover rota dinamicamente via API — tem que ser via redeploy.

**Migração v1 → v2:** instala CRD + operator + RBAC, troca `SPRING_PROFILES_ACTIVE=standalone` por `GATEWAY_ROUTES_STORE_TYPE=configmap`, aplica as CRDs que cobrem o catálogo antigo, rollout restart. Sem migração de dados — o snapshot estático sai de cena.

Roteiro completo: [`TESTE_STANDALONE.md`](TESTE_STANDALONE.md).

---

## Logs e observabilidade

```bash
kubectl logs -n platform deployment/dynamic-gateway        --tail=200 -f
kubectl logs -n platform deployment/gateway-route-operator --tail=200 -f
```

**Linhas-chave do gateway:**

- `Loading routes from route config store` — começou um reload.
- `Route config snapshot loaded version=… snapshotRoutes=…` — leu o snapshot do backend.
- `Routes replaced in memory activeRoutes=… elapsedMs=…` — substituiu o route table.
- `Reload completed snapshotRoutes=N activeRoutes=M totalMs=T (loadMs=… mapMs=… convertMs=… replaceMs=… publishMs=…)` — fim do ciclo. `snapshotRoutes` é quanto veio do backend; `activeRoutes` é quanto ficou ativo no locator. Se divergir, sai um `WARN` separado.
- `Properties mode: change listener is a no-op (routes are static)` — v1 standalone.

**Linhas-chave do operator:**

- `Reconcile started id=…` / `Reconcile id=… listed N GatewayRoutes (V valid, D disabled, I invalid)` / `Reconcile id=… snapshot published to backend` / `Reconcile id=… reload signal sent`.

**Medição de memória sob carga:** `scripts/measure-gateway-memory.sh` (depende de `metrics-server` no cluster).

---

## Stress tests

Dois cenários, dependendo do modo em que o gateway está rodando. Ambos usam o mesmo script k6 (`scripts/k6/load-during-large-snapshot.js`) batendo 20k req/min nas rotas baseline `/httpbin/get` e `/anything/load-test` via o `nginx-lb`.

### v2 com operator — cenário completo (carga + reloads pesados)

Carga sustentada **mais** pushes de snapshot de 60k rotas via `ConfigMap` no meio, pra medir tudo de uma vez: throughput, latência, tempo de reload por pod, propagação. É o cenário pra validar a v2 em condições adversas.

```bash
# Pré-requisito: v2 no ar
make bootstrap               # cluster + operator + gateway + nginx-lb

# Terminal 1 — entry point
make nginx-lb-pf

# Terminal 2 — orquestrador (5min, 3 pushes de 60k rotas)
make stress-test             # = TEAMS=3 ROUTES=20000 scripts/k6/run-load-scenario.sh
```

O orquestrador valida pre-flight, sobe o k6 em background, agenda 3 pushes de snapshot grande nos tempos `60 180 270`s, espera tudo terminar e gera **relatório markdown** em `/tmp/dyngw-load-scenario/report.md` com latências p95/p99, contagem de 5xx/4xx, tempo de reload por pod e veredito ✅/❌ por critério.

Variações comuns (rodando o script direto em vez do alias `make stress-test`):

```bash
# 10min, 5 pushes
DURATION=10m SCHEDULE="60 180 300 420 540" TEAMS=3 ROUTES=20000 \
    scripts/k6/run-load-scenario.sh

# 1 push só, pra observar um único reload pesado
SCHEDULE="60" DURATION=3m TEAMS=3 ROUTES=20000 \
    scripts/k6/run-load-scenario.sh
```

### v1 standalone — só carga sustentada

Em standalone as rotas são estáticas (vivem no `application.yaml` da imagem), então **não tem snapshot pra empurrar**. O que vale medir é o baseline puro de throughput e latência das rotas embarcadas — útil pra comparar com a v2 ou pra confirmar que um rebuild não regrediu performance.

```bash
# Pré-requisito: v1 no ar
make standalone-up           # cluster + gateway + nginx-lb

# Terminal 1 — entry point
make nginx-lb-pf

# Terminal 2 — k6 sustentado direto, sem orquestrador
DURATION=5m BASE_URL=http://localhost:18000 \
    k6 run --summary-export=/tmp/k6-standalone.json \
    scripts/k6/load-during-large-snapshot.js
```

O `setup()` do script já confere `/lb/health` + as 2 rotas baseline antes de começar — passa direto em standalone porque tudo já está em pé. Sumário sai no console + `/tmp/k6-standalone.json`.

Cenário realista de v1 — k6 + rolling restart (simula deploy de nova imagem com cliente batendo):

```bash
# enquanto o k6 do bloco acima estiver rodando, em outro terminal:
sleep 90 && kubectl -n platform rollout restart deployment/dynamic-gateway
sleep 90 && kubectl -n platform rollout restart deployment/dynamic-gateway
```

`maxUnavailable: 0` no Deployment deveria manter `gateway_5xx=0` no sumário do k6. O `max` e o `p99` do `http_req_duration` mostram o custo do rollout pra requests em andamento.

---

## Backend de armazenamento

Persistência atrás de duas interfaces no módulo `shared`:

```java
// lado operator (write)
public interface RouteConfigPublisher { void publish(RouteConfigSnapshot snapshot); }

// lado gateway (read)
public interface RouteConfigProvider { RouteConfigSnapshot load(); }
```

A implementação ativa é selecionada por uma propriedade Spring por módulo:

```yaml
# gateway
gateway.routes.store.type: configmap   # ou s3, ou properties

# operator
operator.routes.store.type: configmap  # ou s3 (não suporta properties — modo standalone não tem operator)
```

| Tipo         | Quem                       | Estado                                    |
|--------------|----------------------------|-------------------------------------------|
| `configmap`  | gateway + operator         | ✅ default, produção                      |
| `properties` | só gateway (v1 standalone) | ✅ produção                               |
| `s3`         | gateway + operator         | ✅ validado no k3d com MinIO              |

O S3 usa `S3RouteConfigPublisher` (PutObject) e `S3RouteConfigProvider` (GetObject) sobre o `SnapshotCodec` compartilhado; o operator sinaliza o reload pelo Service (a réplica que recebe relê na hora) e cada réplica faz HEAD no objeto a cada `poll-interval` (15 s), relendo quando o ETag muda. Para ligar: profile `s3` (`application-s3.yaml`) mais bucket e região por ambiente; `endpoint` + `path-style-access` para S3 compatível (MinIO).

**Nenhuma mudança foi necessária** no CRD, no validator, no `SnapshotBuilder`, no reconciler, no `GatewayRouteReloadService`, no bootstrap runner, no `RouteDefinitionMapper`, no `InMemoryDynamicRouteLocator`, ou no endpoint `/internal/routes/reload`. `StorageAbstractionLeakTest` amarra esse contrato — assert que essas classes **não** importam tipos backend-específicos.

Roteiro S3 (incluindo bucket creation): [`TESTE_S3.md`](TESTE_S3.md); com SSO e MinIO local: [`SSO_CENARIO_S3.md`](SSO_CENARIO_S3.md).

---

## Operator por dentro

> Este é o nível "qual classe faz o quê" pra quem precisa mexer no código do operator. Pra fluxo end-to-end, veja [`TESTE_CONFIGMAP.md`](TESTE_CONFIGMAP.md).

O `operator/` é um operator Kubernetes (Spring Boot 4.1 + Fabric8 Client) que observa o CRD `GatewayRoute`, gera um snapshot canônico, publica num backend (`ConfigMap` ou S3) e dispara um *reload* HTTP no Gateway. Roda sem servlet (`WebApplicationType.NONE`) e é compilável como native image (GraalVM).

### Bootstrap

- `OperatorApplication.java` — `main` constrói o contexto sem servidor web. Registra `RuntimeHints` para reflection no AOT (Fabric8, modelos K8s, AWS SDK).
- `OperatorConfiguration.java` — define todos os beans manualmente (sem `@Component` scan agressivo, o que facilita native image): `Clock`, `GatewayRouteValidator`, `SnapshotBuilder`, `GatewayReloadSignaler`, `GatewayRouteReconciler` e `GatewayRouteInformerRunner`.
- `OperatorProperties.java` — *namespace*, URL de reload e `debounce` (default 200 ms). Sobrescrito por `application.yaml`.

### Modelo do CRD

- `crd/GatewayRoute.java` — Custom Resource tipado do Fabric8 (`group=platform.saca.pags`, `v1alpha1`, plural `gatewayroutes`, shortname `gwr`). Spec sem `status` (`Void`).
- `crd/GatewayRouteSpec.java` — campos: `path`, `targetUri`, `stripPrefix`, `methods`, `enabled`, `team`, `description`. Os dois últimos são metadados opcionais (ownership + resumo); não afetam matching, mas viajam dentro do snapshot e são expostos pelo `/internal/routes`.
- `crd/GatewayRouteList.java` — list type exigido pelo cliente tipado.

### Watch + Debounce (`watch/GatewayRouteInformerRunner.java`)

Implementa `SmartLifecycle` (Spring inicia/desliga no ciclo de vida do contexto).

**Start (`start()`):**
1. Cria `ScheduledExecutorService` single-thread (`gw-reconcile`).
2. Pede ao Fabric8 um **`SharedIndexInformer`** pra `GatewayRoute` no namespace alvo. O Informer mantém um cache local sincronizado via List+Watch.
3. Registra `ResourceEventHandler` com `onAdd/onUpdate/onDelete`. Todos chamam `scheduleReconcile()`.
4. Dispara um reconcile inicial.

**Debounce (`scheduleReconcile()`):** sob `ReentrantLock`, cancela o `ScheduledFuture` pendente e agenda novo `runReconcile` em `debounce.toMillis()`. Efeito: rajadas de eventos colapsam em **uma única passagem**.

**Reconcile pass (`runReconcile()`):** lê o estado **direto do indexer in-memory** (`informer.getIndexer().list()`) — barato e atômico para aquele tick. Fallback pra `client.list()` se o informer já tiver fechado. Delega ao `GatewayRouteReconciler`. Qualquer exceção é capturada pra não derrubar o scheduler.

**Stop:** fecha informer e mata o scheduler.

### Reconciliação (`reconcile/GatewayRouteReconciler.java`)

Pipeline linear com ID monotônico pra correlação de log:

1. `snapshotBuilder.build(routes, clock)` → `Result(snapshot, total, valid, disabled, invalid)`.
2. `publisher.publish(snapshot)` — se lançar `RuntimeException`, loga e **retorna sem sinalizar** (não "acorda" o Gateway pra um estado que não persistiu — invariante crítica de consistência).
3. `signaler.signal()` — POST no Gateway.

### Construção do snapshot (`reconcile/SnapshotBuilder.java`)

- Itera todas as `GatewayRoute`s.
- Pula as com `spec.enabled == false` (conta como `disabled`).
- Valida com `GatewayRouteValidator`; inválidas logam `WARN` e são puladas (conta como `invalid`).
- Converte válidas em `RouteConfigEntry(id, path, targetUri, stripPrefix, methods, true, team, description)`.
- **Ordena por `id`** — sort determinístico → o YAML resultante só muda quando houve mudança real, evitando *churn* de versão e *diff ruim* no backend.
- Empacota em `RouteConfigSnapshot(version=ISO instant, generatedAt=now, routes)`.

`GatewayRouteValidator` checa: nome, `spec` não nulo, `path` e `targetUri` obrigatórios, `stripPrefix >= 0`, e `methods` restritos a `GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS`. Retorna `Optional<String>` com a primeira falha.

### Publicação (`store/`)

#### ConfigMap (`ConfigMapRouteConfigPublisher`)

Serializa snapshot em YAML, comprime com gzip, grava em `binaryData[routes.yaml.gz]` do `ConfigMap` (upsert). Loga `version` e contagem de rotas. Veja a seção sobre [gzip](#otimização-60-mil-rotas-num-configmap-de-1-mib) pra detalhes.

#### S3 (`S3RouteConfigPublisher`)

YAML → bytes → `s3.putObject` na key configurada. Usa `UrlConnectionHttpClient` (sem Netty) pra ficar leve no native.

### Sinalização ao Gateway (`signal/GatewayReloadSignaler.java`)

HTTP POST com corpo `{}` pro `reload-url` configurado (default `http://dynamic-gateway.platform.svc.cluster.local:8080/internal/routes/reload`), via JDK `HttpClient`. Timeouts: connect=2s, request=10s. Erros **não** levantam exceção — apenas logam (o snapshot já foi publicado; o Gateway eventualmente vai puxá-lo no próximo bootstrap).

### Decisões/invariantes notáveis

- **Single-thread scheduler + debounce** → reconciliação serializada por instância; sem corrida entre publish e signal.
- **Publish-then-signal**; falha de publish **não** propaga sinal — invariante crítica de consistência.
- **Sort por `id`** garante snapshot determinístico (diff estável).
- **Informer + indexer local** evita hammering no apiserver a cada evento.
- **GraalVM hints** (`Fabric8RuntimeHints`, `Fabric8ModelRuntimeHints`, `AwsRuntimeHints`, `OperatorRuntimeHints`) registram reflection/proxies pra que CRD model, AWS SDK e cliente K8s funcionem no native image.

### Fluxo end-to-end (resumo)

```
kubectl apply -f gatewayroute.yaml
        │
        ▼
Informer (Fabric8) recebe Watch event
        │  onAdd/Update/Delete
        ▼
scheduleReconcile() ── debounce 200ms ──┐
        │                                │
   (rajadas colapsam)                    │
        ▼                                │
runReconcile() ◄─────────────────────────┘
        │  lista do indexer
        ▼
SnapshotBuilder.build() → valida, filtra, ordena
        │
        ▼
RouteConfigPublisher.publish()   → ConfigMap (gzipado) OU S3 (YAML)
        │  (se falhar, aborta o ciclo, NÃO sinaliza)
        ▼
GatewayReloadSignaler.signal()   → POST /internal/routes/reload
        │
        ▼
Gateway lê snapshot e recarrega route table em memória
```

---

## Otimização: detectar snapshots iguais sem ocupar memória

> _Otimização específica do gateway. Escrita pensando em quem está chegando agora no projeto._

Quando o gateway recebe um aviso de "olha, talvez tenha snapshot novo", precisa decidir: vale recarregar tudo, ou esse snapshot é igual ao que já está em memória? Se for igual, recarregar é trabalho jogado fora — compilar 60 mil rotas de novo gasta CPU, memória temporária, e dispara um evento que faz a SCG reconstruir caches internos.

A primeira versão fazia o óbvio: **guardava uma cópia completa** da lista de rotas do último carregamento e comparava item por item. Funcionava, mas pra 60 mil rotas essa cópia ocupava cerca de **50 MiB residentes**, **o tempo todo**, só pra essa comparação.

A solução é a mesma ideia que o WhatsApp usa pra confirmar se você já tem um arquivo no celular: em vez de comparar byte a byte, calcula uma **"impressão digital"** dos dois lados e compara só as impressões.

Usamos **SHA-256**, que transforma qualquer conteúdo numa sequência fixa de **64 caracteres** (128 bytes). Duas propriedades importantes:

1. **Conteúdos idênticos sempre produzem a mesma impressão.**
2. **É praticamente impossível** dois conteúdos diferentes acidentalmente produzirem a mesma impressão (probabilidade na ordem de 2⁻²⁵⁶ — pra fim prático, zero).

|                               | Antes                            | Depois                       |
|-------------------------------|----------------------------------|------------------------------|
| O que guardamos pra comparar  | a lista completa de 60 mil rotas | 64 caracteres                |
| Memória residente nesse campo | ~50 MiB                          | 128 bytes                    |
| CPU por reload                | comparar item por item           | calcular um hash (poucos ms) |

**Custo**: poucos ms de CPU pra calcular o hash e ~12 MiB de alocação temporária (GC limpa logo depois). **Ganho**: ~50 MiB permanentemente alocados volta pro heap útil.

> Código: [`gateway/src/main/java/com/example/gateway/routes/GatewayRouteReloadService.java`](gateway/src/main/java/com/example/gateway/routes/GatewayRouteReloadService.java), método `hashEntries(...)`.

---

## Otimização: 60 mil rotas num `ConfigMap` de 1 MiB

> _Por que conseguimos armazenar muito mais rotas do que o `ConfigMap` parece permitir._

O `ConfigMap` do Kubernetes tem um limite **duro** de **1 MiB** total — esse limite vem do `etcd`, e não tem como pedir "mais um pouquinho".

Uma rota nossa em YAML fica em torno de **180 bytes**. Pra 60 mil rotas: **~11 MiB de YAML** — onze vezes acima do limite. Mas tem uma coisa interessante: esse YAML é **extremamente repetitivo**. Toda rota tem os mesmos campos; caminhos compartilham prefixos enormes; `targetUri` são todos `http://*.platform.svc.cluster.local:8080`; palavras tipo `true`, `false`, `GET` aparecem dezenas de milhares de vezes.

A solução é **comprimir com gzip** antes de salvar no `ConfigMap` — o mesmo algoritmo que sites usam pra mandar HTML mais rápido. Em texto super repetitivo o ganho é gigante.

|              | Tamanho      |
|--------------|--------------|
| YAML cru     | ~11 MiB      |
| YAML gzipado | **~540 KiB** |
| Compressão   | **~20×**     |

Cabe folgado no limite de 1 MiB, com bastante sobra pra crescer.

**Fluxo:**

1. O operator monta o YAML completo em memória.
2. Comprime com gzip.
3. Salva no campo **`binaryData`** do `ConfigMap` (bytes binários crus; o campo `data`, irmão dele, só aceita texto UTF-8). O nome da entrada termina com `.gz`.
4. O gateway baixa o blob binário, descomprime, parseia YAML e processa as rotas.

**Custo da descompressão**: ~700 ms pra 60 mil rotas — parte de um reload total de ~1 s. Imperceptível na prática. **Ganho**: `ConfigMap` continua viável mesmo pra catálogos grandes; sem isso, qualquer carga real estouraria o limite no primeiro `apply`.

> Código:
> - Codec gzip: [`shared/src/main/java/com/example/shared/routes/SnapshotCodec.java`](shared/src/main/java/com/example/shared/routes/SnapshotCodec.java).
> - Publicação no `ConfigMap`: [`operator/src/main/java/com/example/operator/store/ConfigMapRouteConfigPublisher.java`](operator/src/main/java/com/example/operator/store/ConfigMapRouteConfigPublisher.java).

---

## SSO, autorização por rota e auditoria

Desligado por padrão (`gateway.security.enabled=false`): deploys, scripts e k6 continuam iguais. Conceitos e onde ver tudo no Keycloak: [`SSO_E_AUTORIZACAO.md`](SSO_E_AUTORIZACAO.md); roteiro de teste: [`TESTE_SSO.md`](TESTE_SSO.md); um guia por cenário de rotas: [CRD](SSO_CENARIO_CRD.md), [S3](SSO_CENARIO_S3.md) e [Properties](SSO_CENARIO_PROPERTIES.md). Ligado, o gateway identifica quem chama, aplica a política de cada rota, entrega a identidade pronta ao backend e registra **quem fez o quê e quando**. Desenho completo e decisões em `openspec/changes/add-keycloak-sso-audit/`.

### Dois provedores, um modelo de autorização

A permissão vive em **grupos do AD** (uma OU por aplicação, grupos de leitura/escrita por ferramenta). O gateway não sabe de onde o grupo veio: normaliza tudo em autoridades `group:<nome>` / `role:<nome>`, e a rota declara quais aceita. Trocar de provedor não muda nenhuma `GatewayRoute`.

| `gateway.security.provider` | `keycloak` (default)                                          | `ldap`                                     |
|-----------------------------|---------------------------------------------------------------|--------------------------------------------|
| Login                       | tela do Keycloak (OIDC code + PKCE)                           | tela do gateway em `/_gateway/login`       |
| Papel do Keycloak           | ponte para o AD (LDAP federation + group mapper) e IdP        | não existe                                 |
| Grupos vêm de               | claim `groups` do access token                                | busca direta no diretório                  |
| APIs / contas de serviço    | bearer JWT (client credentials), validado local via JWKS      | não nesta versão                           |
| Sessão                      | cookie JWE com o refresh token; Keycloak é a fonte da verdade | cookie JWE com o DN; revalida no diretório |

Nos dois casos a sessão é um cookie `GW_SESSION` cifrado (JWE `dir`/`A256GCM`, chave em Secret, rotação por `kid`) mais um **cache em memória por réplica**. Não há Redis. Quando o cache de uma réplica não tem a sessão (ou ela expirou), a réplica revalida com o provedor: refresh no Keycloak ou nova busca no diretório. Conta desabilitada ou removida do grupo perde o acesso em até um ciclo (5 min por padrão). O Keycloak **não** é chamado a cada request.

A escolha do provedor acontece no startup, não no build: o Spring AOT congela `@ConditionalOnProperty` ao gerar o native image, então um único `ActiveSecurity` monta o modo ativo e a mesma imagem serve desligado, `keycloak` e `ldap`.

### Política por rota

```yaml
spec:
  path: /kafka-ui/**
  targetUri: http://kafka-ui.tools.svc.cluster.local:8080
  access:
    mode: authenticated          # public | authenticated; omitido = gateway.security.default-access
    anyOf: [group:GG_KAFKAUI_READ, group:GG_KAFKAUI_WRITE]
    forwardToken: false          # true repassa Authorization: Bearer <access token> (keycloak)
```

- Rota sem `access` segue `gateway.security.default-access` (default `authenticated`).
- Browser sem sessão vai para o login; API recebe 401; sem o grupo, 403.
- Leitura vs escrita: duas rotas com o mesmo `path` separadas por `methods` (exemplo em `k8s/samples/routes-sso.yaml`, `/sso/rw/**`).
- A política viaja como metadata da rota, não como filtro: as 60k rotas continuam no caminho rápido do `FastRouteCompiler`.
- O backend recebe `X-Auth-Request-User`, `-Email`, `-Subject`, `-Groups`, `-Roles` (e `-Client-Id` para contas de serviço). Headers com esse prefixo vindos de fora são sempre descartados, e o cookie de sessão do gateway nunca é repassado.
- Endpoints do gateway ficam sob `/_gateway/` (`login`, `logout`, `whoami`, início/callback OIDC); o operator rejeita rotas nesse prefixo.
- `GET /internal/routes` exige `gateway.security.internal.any-of` (default `role:gateway-admin`). `POST /internal/routes/reload` continua aberto in-cluster (o operator chama pelo Service) e o nginx-lb devolve 403 para quem vem de fora.

### Auditoria

`gateway.audit.enabled=true` escreve uma linha JSON por request no logger `AUDIT` (stdout, campos ECS, pronto para SIEM), separada dos logs operacionais (o formato de `Reload completed ...` não muda):

```json
{"@timestamp":"2026-10-27T17:03:12.345Z","event":{"action":"route.access","outcome":"success","reason":"allowed","duration":12345678},
 "user":{"id":"8f1c…","name":"alice","email":"alice@tools.local"},
 "gateway":{"auth":{"method":"session","sid":"4b2e…"},"route":{"id":"kafka-ui","team":"platform-team"},
            "access":{"mode":"authenticated","required":["group:GG_KAFKAUI_READ"],"matched":"group:GG_KAFKAUI_READ"}},
 "http":{"request":{"method":"GET"},"response":{"status_code":200}},"url":{"path":"/kafka-ui/topics"},"source":{"ip":"10.42.0.5"}}
```

Ações: `route.access`, `auth.login_start`, `auth.login`, `auth.logout`, `auth.whoami`, `internal.routes.list`, `internal.routes.reload`, `request.unmatched`. Nunca entram token, cookie, `Authorization`, senha ou query string (esta é opt-in com `gateway.audit.include-query`). O `sid` liga cada acesso ao evento de login do Keycloak, que o realm local também loga.

Mudanças de rota ("quem criou/alterou a `GatewayRoute`") ficam com o audit log do kube-apiserver, que já registra o usuário. Exemplo de policy:

```yaml
apiVersion: audit.k8s.io/v1
kind: Policy
rules:
  - level: RequestResponse
    resources:
      - group: platform.saca.pags
        resources: ["gatewayroutes"]
    verbs: ["create", "update", "patch", "delete"]
```

### Rodando local (k3d)

```bash
HOST_PORT=18080 make cluster        # a porta precisa estar livre: é ela que entra no issuer e no redirect
make load-images && make deploy     # plataforma v2
HOST_PORT=18080 make sso-up         # Keycloak (realm tools) + gateway com provider=keycloak
HOST_PORT=18080 make sso-test       # e2e: login, bearer, multi-réplica, logout, auditoria

HOST_PORT=18080 make ldap-up        # OpenLDAP no lugar do AD + gateway com provider=ldap
HOST_PORT=18080 make ldap-test

make sso-down                       # volta o gateway sem SSO

# snapshot num bucket S3 (MinIO no cluster; S3_BACKEND=aws S3_BUCKET=... para um bucket real)
HOST_PORT=18080 make s3-sso-up              # PROVIDER=ldap para o diretório no lugar do Keycloak
HOST_PORT=18080 make s3-sso-test
make s3-sso-down                    # gateway e operator voltam para a ConfigMap, sem SSO

# v1 standalone com SSO: profile standalone-sso (rotas com `access` embarcadas na imagem, sem CRD)
HOST_PORT=18080 make standalone-sso-up      # PROVIDER=ldap para o diretório no lugar do Keycloak
HOST_PORT=18080 make standalone-sso-test
make standalone-sso-down            # volta o v1 sem SSO
```

A configuração já vem pronta em profiles embarcados na imagem e combináveis: um de provedor (`application-keycloak.yaml`, `application-ldap.yaml`) e, quando o store não é o default, um de cenário. Os manifests de `k8s/sso/` só ativam os profiles e passam a URL pública e os segredos; outro ambiente sobrescreve por variável, sem rebuild.

| Cenário de rotas | Rotas vêm de | Keycloak | LDAP | Guia |
|---|---|---|---|---|
| CRD (v2) | `GatewayRoute` → ConfigMap | `keycloak` | `ldap` | [`SSO_CENARIO_CRD.md`](SSO_CENARIO_CRD.md) |
| S3 (v2) | `GatewayRoute` → objeto S3 | `s3,keycloak` | `s3,ldap` | [`SSO_CENARIO_S3.md`](SSO_CENARIO_S3.md) |
| Properties (v1) | `application-standalone-sso.yaml`, na imagem | `standalone-sso,keycloak` | `standalone-sso,ldap` | [`SSO_CENARIO_PROPERTIES.md`](SSO_CENARIO_PROPERTIES.md) |

Usuários locais: `alice` (tools-httpbin), `bob` (sem grupo), `carol` (tools-admins). A senha é gerada no Secret `sso-secrets` e impressa pelo `sso-up`. Detalhes manuais em [`TESTE_SSO.md`](TESTE_SSO.md).

### Ligando no AD de verdade

- **Via Keycloak** (`provider=keycloak`): no realm, *User federation → LDAP* apontando para o AD (com Kerberos/SPNEGO se quiser login transparente) e um mapper *group-ldap-mapper* restrito à OU da aplicação. Os grupos chegam no claim `groups`; nada muda no gateway.
- **Direto** (`provider=ldap`):

```yaml
gateway:
  security:
    provider: ldap
    ldap:
      url: ldaps://ad.corp.local:636
      trusted-certificates: /etc/ssl/corp-ca.pem
      bind-dn: CN=svc-tools-gateway,OU=Services,DC=corp,DC=local   # somente leitura
      user-search: { base: "OU=Users,DC=corp,DC=local", filter: "(sAMAccountName={0})" }
      username-attribute: sAMAccountName
      subject-attribute: objectGUID
      group-search: { base: "OU=Tools,OU=Groups,DC=corp,DC=local", filter: "(member:1.2.840.113556.1.4.1941:={0})" }
```

O filtro `1.2.840.113556.1.4.1941` resolve grupos aninhados no AD. A base de grupos restrita à OU da aplicação limita as autoridades ao que o IDM mapeou. O limite de tentativas de login (`ldap.login-attempts`) é por réplica: com N réplicas, um usuário pode errar até N × `max-failures` vezes na janela antes do 429. A política de lockout do AD continua valendo por cima.

---

## Limitações conhecidas

Este POC **explicitamente não implementa**:

- Rate limiting, circuit breakers, retries, timeouts por rota, TLS.
- Suporte multi-namespace ou multi-gateway.
- CRD `status.conditions` (`Accepted`, `Invalid`, `Published`, `FailedToReload`). Feedback de validação fica só nos logs do operator.
- Rollback de snapshot, assinatura/checksum.
- Backend S3 implementado de verdade — só o stub.
- Health checks de targets.

O operator roda como **réplica única**. Com mais de uma, vale last-writer-wins.

---

## Próximos passos

1. CRD `status.conditions` pra `kubectl describe gatewayroute` refletir validação + publish state.
2. Per-route timeout, retries, rate limit no CRD e no snapshot.
3. Back-channel logout (revogação imediata) e bearer/basic para contas de serviço no modo `ldap`.
4. Backend S3 real + versionamento de snapshot.
5. Detecção de conflito de path entre rotas durante reconcile.
6. Trocar o sinal HTTP por watch direto do `ConfigMap` no gateway (ou polling do S3) pra self-detect snapshot novo sem precisar do POST.
7. Multi-gateway (label-based routing de CRDs pra gateways específicos).
8. Audit trail de mudanças de snapshot.
9. Leader election no operator pra escalar além de uma réplica.
10. (Quando dor de memória real chegar) implementar a proposta `reduce-gateway-route-memory` — ver `openspec/changes/`.

---

## Licença

POC interno.
