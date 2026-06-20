# TESTE_STANDALONE.md — Validação localhost da v1 (sem operator/CRD)

Roteiro pra subir o gateway no modo **standalone** (rotas estáticas em `application.yaml` embarcado na imagem) e validar o ciclo completo de roteamento + restart-recovery.

Esse é o modo mais simples — não tem operator, não tem CRD, não tem ConfigMap dinâmico. Use quando o cliente ainda não tem CRD/operator instalados e quer só "rotear chamadas" via gateway.

Pra o modo com CRD + operator + hot-reload, veja [`TESTE_CONFIGMAP.md`](TESTE_CONFIGMAP.md).

---

## Pré-requisitos

### Host

- Docker rodando (`docker info` responde).
- `k3d` instalado (`k3d version`).
- `kubectl` apontando pra um cluster onde você pode mexer.
- JDK 25 (GraalVM Community recomendado pra `nativeCompile`).
- Porta `8080` livre.

### Editor mental

- v1 standalone significa: **rotas vivem no arquivo `gateway/src/main/resources/application-standalone.yaml`** e são empacotadas dentro da imagem nativa do gateway.
- Trocar rota = editar esse YAML, rebuild da imagem, rollout restart. Sem hot-reload.
- Não tem operator, então não vai aparecer pod chamado `gateway-route-operator`.

---

## Setup de janelas

Vou usar 2 terminais ao longo do roteiro:

- **Terminal A:** comandos `kubectl` e curl.
- **Terminal B:** `kubectl port-forward` (foreground; Ctrl+C derruba).

---

## Fase 0 — Tear down (se estiver vindo de outra demo)

Se já tem cluster antigo rodando, limpa antes:

```bash
make down                # destroi o cluster k3d
docker ps -a | grep dyngw # confirma que nenhum container do projeto sobrou
```

---

## Fase 1 — Cluster e imagens

```bash
make cluster             # cria o cluster k3d com TZ=America/Sao_Paulo
make load-images         # gradle nativeCompile + docker build + k3d import
```

`load-images` builda **as duas** imagens (`dynamic-gateway:local` e `dynamic-gateway-operator:local`) e importa pro cluster. Em v1 só usaremos a primeira; o operator fica importado mas não deployado.

Confirma que o cluster tem a imagem:

```bash
docker exec k3d-dynamic-gateway-lab-server-0 crictl images | grep dynamic-gateway
# dynamic-gateway              local   <hash>  ~XMB
# dynamic-gateway-operator     local   <hash>  ~XMB
```

---

## Fase 2 — Subir o backend de teste

O sample de rotas v1 aponta pro `go-httpbin` rodando dentro do cluster. Sobe ele primeiro:

```bash
kubectl apply -f k8s/samples/httpbin-in-cluster.yaml
kubectl -n platform rollout status deployment/go-httpbin --timeout=60s
```

> **Por que não como Ingress externo?** Pra evitar resolução de DNS público ou pull do upstream durante o teste. O go-httpbin vive dentro do cluster e responde via DNS interno (`go-httpbin.platform.svc.cluster.local`).

---

## Fase 3 — Subir o gateway em modo standalone

```bash
make standalone-up
```

O alvo faz, em ordem:

1. `kubectl apply -f k8s/namespace.yaml` (idempotente)
2. `kubectl apply -f k8s/samples/httpbin-in-cluster.yaml` + aguarda rollout
3. `kubectl apply -f k8s/gateway-standalone/` (Deployment + Service + Ingress) + aguarda rollout
4. `kubectl apply -f k8s/nginx-lb/` (Deployment + Service + ConfigMap + headless service) + aguarda rollout

O **nginx-lb** entra como parte do mesmo target porque é o **único ponto de exposição externa** do projeto — pods do gateway não são acessados diretamente.

Confirma o estado:

```bash
kubectl -n platform get pods,svc
# 2 pods do go-httpbin (Ready 1/1)
# 2 pods do dynamic-gateway (Ready 1/1, SPRING_PROFILES_ACTIVE=standalone)
# svc/go-httpbin (ClusterIP), svc/dynamic-gateway (ClusterIP)
```

---

## Fase 4 — Validar o startup do gateway

Logs do gateway devem mostrar a sequência canônica do modo properties:

```bash
kubectl -n platform logs deploy/dynamic-gateway --tail=100 | grep -E "Properties|standalone|Reload completed|Gateway bootstrap"
```

Esperado:

```
Properties route config provider initialized version=v1-sample routes=2
Properties mode: change listener is a no-op (routes are static)
Route config snapshot loaded version=v1-sample snapshotRoutes=2 elapsedMs=...
Routes replaced in memory activeRoutes=2 elapsedMs=...
Reload completed snapshotRoutes=2 activeRoutes=2 totalMs=... (...)
Gateway bootstrap completed activeRoutes=2
```

Se aparecer `Reload completed snapshotRoutes=0`, o `application-standalone.yaml` não foi carregado — provavelmente faltou `SPRING_PROFILES_ACTIVE=standalone`. Confirma:

```bash
kubectl -n platform exec deploy/dynamic-gateway -- env | grep PROFILES
# SPRING_PROFILES_ACTIVE=standalone
```

---

## Fase 5 — Tráfego real

**Terminal B:**

```bash
make nginx-lb-pf      # port-forward svc/nginx-lb -> localhost:18000
```

**Terminal A:**

```bash
# Lista as rotas em memória — deve ter as 2 baseline (httpbin + anything)
curl -s http://localhost:18000/internal/routes | python3 -m json.tool

# Tráfego pelo gateway (via nginx-lb, que distribui entre os pods):
curl -s http://localhost:18000/httpbin/get  | python3 -m json.tool    # HTTP 200
curl -s http://localhost:18000/anything/foo | python3 -m json.tool    # HTTP 200

# Path que não existe no catálogo:
curl -i http://localhost:18000/desconhecido/x                          # HTTP 404
```

> Pra confirmar que o nginx-lb está rodando o round-robin entre os 2 pods do gateway:
> ```bash
> for i in {1..10}; do
>   curl -sI http://localhost:18000/httpbin/get | awk 'tolower($1)=="x-upstream:"{print $2}'
> done | tr -d '\r' | sort | uniq -c
> ```
> Deve aparecer 2 IPs diferentes, ~50/50.

`team` e `description` aparecem na resposta `/internal/routes` (são opcionais e só aparecem quando declarados):

```json
{
  "routes": [
    {
      "id": "httpbin-route",
      "uri": "http://go-httpbin.platform.svc.cluster.local:8080",
      "predicates": ["Path=/httpbin/**", "Method=GET,POST"],
      "filters": ["StripPrefix=1"],
      "team": "platform-team",
      "description": "Echoes the request via httpbin for smoke testing."
    },
    ...
  ]
}
```

---

## Fase 6 — Restart-recovery

O gateway deve voltar a servir as mesmas rotas após restart, sem intervenção externa:

```bash
kubectl -n platform rollout restart deployment/dynamic-gateway
kubectl -n platform rollout status   deployment/dynamic-gateway --timeout=120s

# Port-forward continua válido porque ele aponta pra svc/nginx-lb, que
# não foi tocada. nginx-lb usa headless DNS pra alcançar os pods do gateway;
# ele apenas vê os pods novos quando o DNS resolver atualiza (valid=5s).
curl -s http://localhost:18000/httpbin/get | python3 -m json.tool      # HTTP 200
```

Não tem `ConfigMap` envolvido — as rotas vêm do `application-standalone.yaml` empacotado dentro da imagem nativa. Restart de pod sempre devolve o mesmo catálogo.

---

## Fase 7 — Trocar uma rota

Edita `gateway/src/main/resources/application-standalone.yaml` (por exemplo, mudar `stripPrefix` de uma rota), depois:

```bash
make load-images    # rebuild da imagem + re-import no cluster
kubectl -n platform rollout restart deployment/dynamic-gateway
kubectl -n platform rollout status   deployment/dynamic-gateway --timeout=180s

# Confirma (via nginx-lb)
curl -s http://localhost:18000/internal/routes | python3 -m json.tool
```

A janela de unavailability é a do rolling update (default 5-10 s com 2 réplicas e `maxUnavailable: 0`). Durante o rollout, requests pegam o pod antigo até ele ser drenado, então o roteamento não interrompe.

---

## O que NÃO funciona em v1

- **`kubectl apply` de um CRD `GatewayRoute`** — sem CRD instalado, o apiserver responde `the server doesn't have a resource type "gatewayroute"`. Se você precisa de CRD, vá pra v2 ([`TESTE_CONFIGMAP.md`](TESTE_CONFIGMAP.md)).
- **`POST /internal/routes/reload`** dispara um reload mas o snapshot que ele lê é o mesmo cacheado em memória — efetivamente no-op. Aparece no log como `Snapshot routes unchanged hash=... skipping reload`. **Isso é correto** — em v1 o snapshot é estático até o próximo deploy.
- **Adicionar/remover rota dinamicamente via API** — tem que ser via redeploy.

---

## Troubleshooting

### Gateway sobe mas `routes=0`

```bash
kubectl -n platform exec deploy/dynamic-gateway -- env | grep PROFILES
```

Se `SPRING_PROFILES_ACTIVE` não estiver `standalone`, o profile não foi ativado e o gateway tenta o default `type=configmap` — que falha sem ConfigMap. Confira `k8s/gateway-standalone/deployment.yaml`.

### Pod do gateway em `CrashLoopBackOff`

YAML malformado em `application-standalone.yaml` quebra o bind do `@ConfigurationProperties`. Veja o log:

```bash
kubectl -n platform logs deploy/dynamic-gateway --tail=80 | grep -iE "error|exception"
```

Conserta o YAML, `make load-images`, `kubectl rollout restart`.

### `make load-images` falha com "cannot find -lz"

Falta o `libz-dev` na máquina (GraalVM `nativeCompile` linka contra zlib). No Ubuntu:

```bash
sudo apt-get install -y libz-dev libstdc++-12-dev
```

Depois retenta `make load-images`.

### `make load-images` muito lento

`nativeCompile` do GraalVM demora 2-5 min em laptop. Se você só mexeu em config (YAML/properties), considere usar `bootJar` em vez de native pra ciclos rápidos (mas a imagem standalone do projeto é nativa por design).

---

## Cleanup

```bash
make standalone-down     # remove gateway + nginx-lb
                         # NÃO mexe no namespace nem no go-httpbin
# OU
make down                # destrói o cluster k3d inteiro
```

---

## Migração v1 → v2

Quando o cliente quiser hot-reload e multi-time (cada um aplicando suas próprias CRDs), siga a v2:

1. Aplica CRD + operator + RBAC: `kubectl apply -f k8s/crd/ -f k8s/operator/ -f k8s/gateway/rbac.yaml`.
2. Troca a env do gateway: remove `SPRING_PROFILES_ACTIVE=standalone`, deixa o default (`gateway.routes.store.type=configmap`).
3. Aplica `k8s/gateway/deployment.yaml` (substitui o `k8s/gateway-standalone/deployment.yaml`).
4. `kubectl rollout restart deployment/dynamic-gateway`.
5. Aplica as `GatewayRoute` CRDs equivalentes ao catálogo que estava em properties.

Sem migração de dados — o snapshot estático sai de cena no momento do rollout. Roteiro completo em [`TESTE_CONFIGMAP.md`](TESTE_CONFIGMAP.md).
