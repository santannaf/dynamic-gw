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
10. [Backend de armazenamento](#backend-de-armazenamento)
11. [Operator por dentro](#operator-por-dentro)
12. [Otimização: detectar snapshots iguais sem ocupar memória](#otimização-detectar-snapshots-iguais-sem-ocupar-memória)
13. [Otimização: 60 mil rotas num `ConfigMap` de 1 MiB](#otimização-60-mil-rotas-num-configmap-de-1-mib)
14. [Limitações conhecidas](#limitações-conhecidas)
15. [Próximos passos](#próximos-passos)
16. [Licença](#licença)

---

## Modos de entrega: v1 standalone vs v2 com operator

Duas formas de rodar o gateway, decididas por uma única propriedade (`gateway.routes.store.type`):

| Característica | **v1 standalone** | **v2 com operator** |
|---|---|---|
| Rotas declaradas em | `application.yaml` do gateway | CRDs `GatewayRoute` no cluster |
| CRD instalado | ❌ | ✅ |
| Operator deployado | ❌ | ✅ |
| RBAC pra `ConfigMap`-watch | ❌ não precisa | ✅ |
| Trocar rota sem restart | ❌ exige rollout | ✅ ~250 ms via watcher |
| Multi-time (cada um aplicando CRDs) | ❌ | ✅ |
| Manifests deployados | `k8s/gateway-standalone/` | `k8s/namespace.yaml` + `k8s/crd/` + `k8s/operator/` + `k8s/gateway/` |
| Guia detalhado | [`TESTE_STANDALONE.md`](TESTE_STANDALONE.md) | [`TESTE_CONFIGMAP.md`](TESTE_CONFIGMAP.md) |
| Hot-reload via S3 | – | [`TESTE_S3.md`](TESTE_S3.md) (stub) |

Os dois modos compartilham 100% do pipeline downstream (`RouteConfigEntry` → `RouteDefinitionMapper` → `FastRouteCompiler` → `InMemoryDynamicRouteLocator`). Só a fonte do snapshot muda — `PropertiesRouteConfigProvider` vs `ConfigMapRouteConfigProvider` vs (futuramente) `S3RouteConfigProvider`.

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

| Responsabilidade | Implementação |
|---|---|
| Fonte da verdade declarativa | CRDs `GatewayRoute` no namespace `platform` (v2) / `application.yaml` (v1) |
| Estado materializado (hoje) | YAML gzipado em `ConfigMap platform/gateway-routes` (v2) / properties (v1) |
| Estado materializado (futuro) | YAML em S3 — mesma interface, plug-in (`S3RouteConfigProvider`) |
| Estado em runtime | Mapa de `Route`s na memória do pod do gateway |

O sinal HTTP (`POST /internal/routes/reload`) é **best-effort**. Ele **não é** a fonte da verdade — o bootstrap runner do gateway lê o snapshot mais recente no startup do pod, então um sinal perdido se cura no próximo restart ou no próximo reconcile bem-sucedido.

---

## Layout dos módulos

```
shared/    # modelo storage-agnostic: RouteConfigSnapshot, RouteConfigEntry, SnapshotCodec
gateway/   # Spring Cloud Gateway + providers (ConfigMap / S3 stub / Properties)
operator/  # Fabric8 informer + reconciler + publisher (ConfigMap / S3 stub)
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

| Tipo | Quem | Estado |
|---|---|---|
| `configmap` | gateway + operator | ✅ default, produção |
| `properties` | só gateway (v1 standalone) | ✅ produção |
| `s3` | gateway + operator | 🚧 stub — `UnsupportedOperationException` |

Implementar S3 de verdade requer apenas:

1. Trocar `S3RouteConfigPublisher` (PutObject) e `S3RouteConfigProvider` (GetObject) pelo corpo real, contra o `SnapshotCodec` compartilhado.
2. Configurar `gateway.routes.store.type=s3` + `gateway.routes.store.s3.*` (bucket/key/region).

**Nenhuma mudança requerida** no CRD, no validator, no `SnapshotBuilder`, no reconciler, no `GatewayRouteReloadService`, no bootstrap runner, no `RouteDefinitionMapper`, no `InMemoryDynamicRouteLocator`, ou no endpoint `/internal/routes/reload`. `StorageAbstractionLeakTest` amarra esse contrato — assert que essas classes **não** importam tipos backend-específicos.

Roteiro experimental S3 (incluindo bucket creation): [`TESTE_S3.md`](TESTE_S3.md).

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

#### S3 (`S3RouteConfigPublisher`) — stub

YAML → bytes → `s3.putObject`. Atualmente lança `UnsupportedOperationException`. Esqueleto pronto pra implementação real; usa `UrlConnectionHttpClient` (sem Netty) pra ficar leve no native.

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

| | Antes | Depois |
|---|---|---|
| O que guardamos pra comparar | a lista completa de 60 mil rotas | 64 caracteres |
| Memória residente nesse campo | ~50 MiB | 128 bytes |
| CPU por reload | comparar item por item | calcular um hash (poucos ms) |

**Custo**: poucos ms de CPU pra calcular o hash e ~12 MiB de alocação temporária (GC limpa logo depois). **Ganho**: ~50 MiB permanentemente alocados volta pro heap útil.

> Código: [`gateway/src/main/java/com/example/gateway/routes/GatewayRouteReloadService.java`](gateway/src/main/java/com/example/gateway/routes/GatewayRouteReloadService.java), método `hashEntries(...)`.

---

## Otimização: 60 mil rotas num `ConfigMap` de 1 MiB

> _Por que conseguimos armazenar muito mais rotas do que o `ConfigMap` parece permitir._

O `ConfigMap` do Kubernetes tem um limite **duro** de **1 MiB** total — esse limite vem do `etcd`, e não tem como pedir "mais um pouquinho".

Uma rota nossa em YAML fica em torno de **180 bytes**. Pra 60 mil rotas: **~11 MiB de YAML** — onze vezes acima do limite. Mas tem uma coisa interessante: esse YAML é **extremamente repetitivo**. Toda rota tem os mesmos campos; caminhos compartilham prefixos enormes; `targetUri` são todos `http://*.platform.svc.cluster.local:8080`; palavras tipo `true`, `false`, `GET` aparecem dezenas de milhares de vezes.

A solução é **comprimir com gzip** antes de salvar no `ConfigMap` — o mesmo algoritmo que sites usam pra mandar HTML mais rápido. Em texto super repetitivo o ganho é gigante.

| | Tamanho |
|---|---|
| YAML cru | ~11 MiB |
| YAML gzipado | **~540 KiB** |
| Compressão | **~20×** |

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

## Limitações conhecidas

Este POC **explicitamente não implementa**:

- Auth por rota, rate limiting, circuit breakers, retries, timeouts por rota, TLS.
- Suporte multi-namespace ou multi-gateway.
- CRD `status.conditions` (`Accepted`, `Invalid`, `Published`, `FailedToReload`). Feedback de validação fica só nos logs do operator.
- Rollback de snapshot, audit log, assinatura/checksum.
- Backend S3 implementado de verdade — só o stub.
- Health checks de targets.

O operator roda como **réplica única**. Com mais de uma, vale last-writer-wins.

---

## Próximos passos

1. CRD `status.conditions` pra `kubectl describe gatewayroute` refletir validação + publish state.
2. Per-route timeout, retries, rate limit no CRD e no snapshot.
3. Auth / scopes por rota.
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
