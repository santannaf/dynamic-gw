# Operator – Visão Geral

O módulo é um **operador Kubernetes** (Spring Boot 4.1 + Fabric8 Client) que observa o CRD `GatewayRoute`, gera um snapshot canônico das rotas válidas, publica esse snapshot num backend (ConfigMap ou S3) e dispara um *reload* HTTP no Gateway. Roda sem servlet (`WebApplicationType.NONE`) e é compilável como native image (GraalVM).

## 1. Bootstrap

- `OperatorApplication.java:13` — `main` constrói o contexto sem servidor web. Registra `RuntimeHints` para reflection no AOT (Fabric8, modelos K8s, AWS SDK).
- `OperatorConfiguration.java:18` — define todos os beans manualmente (sem `@Component` scan agressivo, o que facilita native image): `Clock`, `GatewayRouteValidator`, `SnapshotBuilder`, `GatewayReloadSignaler`, `GatewayRouteReconciler` e o `GatewayRouteInformerRunner`.
- `OperatorProperties.java:8` — *namespace*, URL de reload e `debounce` (default 200ms). Sobrescrito por `application.yaml`.

## 2. Modelo do CRD

- `crd/GatewayRoute.java:18` — Custom Resource tipado do Fabric8 (`group=platform.saca.pags`, `v1alpha1`, plural `gatewayroutes`, shortname `gwr`). Spec sem `status` (`Void`).
- `crd/GatewayRouteSpec.java:5` — campos: `path`, `targetUri`, `stripPrefix`, `methods`, `enabled`.
- `crd/GatewayRouteList.java:5` — list type exigido pelo cliente tipado.

## 3. Watch + Debounce (`watch/GatewayRouteInformerRunner.java`)

Implementa `SmartLifecycle` (Spring inicia/desliga no ciclo de vida do contexto).

**Start (`start()` em :47)**
1. Cria `ScheduledExecutorService` single-thread (`gw-reconcile`, non-daemon).
2. Pede ao Fabric8 um **SharedIndexInformer** para `GatewayRoute` no namespace alvo (:56). O Informer mantém um cache local sincronizado via List+Watch.
3. Registra `ResourceEventHandler` com `onAdd/onUpdate/onDelete`. Todos chamam `scheduleReconcile()`.
4. Dispara um reconcile inicial (sincroniza estado mesmo sem evento).

**Debounce (`scheduleReconcile()` em :105)**
- Sob `ReentrantLock`: cancela o `ScheduledFuture` pendente (se houver) e agenda novo `runReconcile` para daqui a `debounce.toMillis()`. Efeito: rajadas de eventos colapsam em **uma única passagem**.

**Reconcile pass (`runReconcile()` em :121)**
- Lê o estado **direto do indexer in-memory** do Informer (`informer.getIndexer().list()`) — barato e atômico para aquele tick. Fallback para `client.list()` caso o informer já tenha sido fechado.
- Delega ao `GatewayRouteReconciler`. Qualquer exceção é capturada para não derrubar o scheduler.

**Stop (`stop()` em :83)** — fecha informer e mata o scheduler.

## 4. Reconciliação (`reconcile/GatewayRouteReconciler.java`)

Em :34, segue um pipeline linear curto, com ID monotônico (`AtomicLong`) para correlação nos logs:

1. `snapshotBuilder.build(routes, clock)` → `Result(snapshot, total, valid, disabled, invalid)`.
2. `publisher.publish(snapshot)` — se lançar `RuntimeException`, loga e **retorna sem sinalizar** (não "acorda" o Gateway para um estado que não persistiu).
3. `signaler.signal()` — POST no Gateway.

## 5. Construção do snapshot (`reconcile/SnapshotBuilder.java`)

Em :25:
- Itera todas as `GatewayRoute`s.
- Pula as com `spec.enabled == false` (conta como `disabled`).
- Valida com `GatewayRouteValidator`; inválidas viram log `WARN` e são puladas (conta como `invalid`).
- Converte válidas em `RouteConfigEntry(id=name, path, targetUri, stripPrefix, methods, enabled=true)` (:52).
- **Ordena por `id`** — sort determinístico ⇒ o YAML resultante só muda quando houve mudança real, evitando *churn* de versão e *diff ruim* em ConfigMap/S3.
- Empacota em `RouteConfigSnapshot(version=ISO instant, generatedAt=now, routes)`.

`GatewayRouteValidator.java:15`: checa nome, `spec` não nulo, `path` e `targetUri` obrigatórios, `stripPrefix >= 0`, e `methods` restritos a `GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS`. Retorna `Optional<String>` com a primeira falha.

## 6. Publicação do snapshot (`store/`)

Estratégia desacoplada via interface `RouteConfigPublisher.publish(snapshot)` (:5).

### Seleção do backend (`store/RouteConfigStoreConfiguration.java`)

Comentário em :17 documenta uma decisão arquitetural importante:
- **Não** usa `@ConditionalOnProperty` porque o **Spring AOT congela a condição em build-time** do native image — o ramo perdedor desaparece do binário.
- Em vez disso, registra **ambos** os clients incondicionalmente (`KubernetesClient` e `S3Client`) e o método-fábrica do publisher (:64) faz um `if (type == "s3")` em runtime. Custo de manter os dois clients ociosos é zero — só abrem conexão na primeira chamada.

### ConfigMap (`ConfigMapRouteConfigPublisher.java:28`)
- Serializa snapshot em YAML via `SnapshotCodec`.
- Tenta `get()` no ConfigMap; se nulo → `create()`, senão → `update()` (upsert tipado do Fabric8). Loga `version` e contagem de rotas.

### S3 (`S3RouteConfigPublisher.java:30`)
- YAML → bytes UTF-8.
- `PutObjectRequest` com `contentType=application/yaml`, `contentLength` setado (necessário p/ `UrlConnectionHttpClient`).
- `s3.putObject(...)`. Sem versionamento de objeto — o backend pode (deve) ter versioning ativado no bucket se quiser histórico.

S3 usa `UrlConnectionHttpClient` (sem Netty) e `URL_CONNECTION` — pega menos peso e funciona melhor em native. `pathStyleAccess` controla compat com MinIO/LocalStack.

## 7. Sinalização ao Gateway (`signal/GatewayReloadSignaler.java`)

Em :32: HTTP POST com corpo `{}` para o `reload-url` configurado (`http://dynamic-gateway.platform.svc.cluster.local:8080/internal/routes/reload` por default), usando `HttpClient` da JDK. Timeouts: connect=2s, request=10s. Erros NÃO levantam exceção — apenas logam (já publicamos; o Gateway eventualmente vai puxar o snapshot do backend de qualquer forma).

## 8. Contratos compartilhados (`shared/`)

- `RouteConfigSnapshot.java:6` — record imutável `(version, generatedAt, routes)`; usa `List.copyOf` no compact constructor para tornar a lista *defensiva*.
- `RouteConfigEntry.java:5` — record com cópia defensiva de `methods`.
- `SnapshotCodec.java:10` — wrapper de Jackson 3 (`tools.jackson.*`) para YAML/JSON, desabilita timestamps numéricos e `FAIL_ON_UNKNOWN_PROPERTIES`. É o **único formato de borda** entre Operator → backend → Gateway.

## Fluxo completo (resumo de um ciclo)

```
kubectl apply -f gatewayroute.yaml
        │
        ▼
Informer (Fabric8) recebe Watch event
        │  onAdd/Update/Delete
        ▼
scheduleReconcile()  ── debounce 200ms ──┐
        │                                │
   (rajadas colapsam)                    │
        ▼                                │
runReconcile() ◄─────────────────────────┘
        │  lista do indexer
        ▼
SnapshotBuilder.build() → valida, filtra, ordena
        │
        ▼
RouteConfigPublisher.publish()   → ConfigMap OU S3 (YAML)
        │  (se falhar, aborta o ciclo, NÃO sinaliza)
        ▼
GatewayReloadSignaler.signal()   → POST /internal/routes/reload
        │
        ▼
Gateway lê snapshot e recarrega
```

## Decisões/invariantes notáveis

- **Single-thread scheduler + debounce** → reconciliação serializada por instância; sem corrida entre publish e signal.
- **Publish-then-signal**; falha de publish **não** propaga sinal — invariante crítica de consistência.
- **Sort por `id`** garante snapshot determinístico (diff estável).
- **Informer + indexer local** evita hammering na API server a cada evento.
- **Dois clients sempre registrados** é necessidade do AOT/native — explicada em `RouteConfigStoreConfiguration.java:17`.
- **GraalVM hints** (`Fabric8RuntimeHints`, `Fabric8ModelRuntimeHints`, `AwsRuntimeHints`, `OperatorRuntimeHints`) registram reflection/proxies para que CRD model, AWS SDK e cliente K8s funcionem no native image.

Resumo: Watch → debounce → list-from-cache → validate+sort → publish → signal. Tudo orquestrado num pipeline single-thread, com escolha de backend (CM/S3) decidida em runtime e contratos canônicos no módulo `shared`.
