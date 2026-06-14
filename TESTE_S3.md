# TESTE_S3.md — Validação localhost com backend S3 (AWS, IAM User)

Guia self-contained para subir o POC em k3d com o snapshot de rotas vivendo
em S3 ao invés de ConfigMap. Para a demo com ConfigMap, veja `TESTE_LOCAL.md`.

A topologia é idêntica à da demo ConfigMap — três componentes (CRD, operator,
gateway) — só muda a "memória" do plano de controle: ao invés de uma
ConfigMap dentro do cluster, é um objeto YAML em `s3://<bucket>/snapshots/routes.yaml`.

---

## Pré-requisitos

### No host

- Linux com `sudo` (para `zlib1g-dev`/`build-essential` do nativeCompile)
- Docker Engine rodando
- k3d e kubectl instalados
- GraalVM 25 (`java -version` mostra `GraalVM CE 25.x`)
- `zlib1g-dev` e `build-essential` (`sudo apt install -y zlib1g-dev build-essential`)
- `aws` CLI instalado (`aws --version`) — opcional mas usado nas verificações

### Na AWS

- IAM User com Access Key + Secret Key — credenciais em mãos
- (Bucket S3 ainda não precisa existir; o script da Fase 1.b cria.)

### Exportar credenciais AWS no shell de partida

```bash
export AWS_ACCESS_KEY_ID=AKIA...
export AWS_SECRET_ACCESS_KEY=...
export AWS_DEFAULT_REGION=us-east-1   # ajuste se sua região for outra
```

Confirme a conta antes de seguir:

```bash
aws sts get-caller-identity
# {
#   "UserId": "AIDA...",
#   "Account": "123456789012",
#   "Arn": "arn:aws:iam::123456789012:user/seu-iam-user"
# }
```

### Criar o bucket S3 com o script do projeto

```bash
./scripts/create-s3-bucket.sh

# Sai algo como:
#   Bucket    : dynamic-gateway-routes-poc-123456789012
#   Região    : us-east-1
#   Versionado: true
#
# Policy mínima pro IAM User do POC salva em:
#   /tmp/dynamic-gateway-routes-poc-123456789012-iam-policy.json
```

O script:
- Deriva o nome do bucket do seu Account ID (garante unicidade global)
- Cria o bucket se ainda não existir (idempotente)
- Bloqueia acesso público (baseline de segurança)
- Habilita versionamento (útil pra rollback de snapshot)
- Gera o JSON da policy mínima em `/tmp/...-iam-policy.json` pra você anexar
  ao IAM User antes de seguir

Exporte as duas envs que o restante do guia usa:

```bash
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
export S3_BUCKET=dynamic-gateway-routes-poc-${ACCOUNT_ID}
export S3_REGION=us-east-1

aws s3 ls s3://$S3_BUCKET/
# Sai vazio. Beleza.
```

---

## Setup de janelas

Recomendo 4 terminais abertos em paralelo:

| Janela  | Para que serve                                                  |
|---------|-----------------------------------------------------------------|
| **A**   | Comandos principais (kubectl/aws/curl)                          |
| **B**   | `kubectl logs -f deployment/gateway-route-operator -n platform` |
| **C**   | `kubectl logs -f deployment/dynamic-gateway -n platform`        |
| **D**   | `watch -n 2 'aws s3 ls s3://$S3_BUCKET/snapshots/'`             |

As janelas B/C/D só ficam úteis depois da Fase 6.

---

## Fase 0 — Tear down (se vier de outra demo)

```bash
./scripts/delete-cluster.sh

# Limpa objetos antigos do bucket pra começar do zero (CUIDADO se for bucket
# compartilhado — confine ao prefixo snapshots/):
aws s3 rm s3://$S3_BUCKET/snapshots/ --recursive
```

---

## Fase 1 — Cluster e namespace

```bash
./scripts/create-cluster.sh
kubectl apply -f k8s/namespace.yaml
kubectl get ns platform
```

Espere o `platform` ficar `Active`.

---

## Fase 2 — Build dos binários nativos e imagens Docker

> Esta fase é idêntica à Fase 2 do TESTE_LOCAL.md. Se você acabou de fazer
> a demo ConfigMap e nenhum código mudou, **pode pular** — as imagens
> `dynamic-gateway:local` e `dynamic-gateway-operator:local` ainda estão no
> k3d. Recompile só se o código de S3 mudou.

```bash
./gradlew :gateway:nativeCompile :operator:nativeCompile -x test
```

```bash
docker build -t dynamic-gateway:local -f gateway/Dockerfile .
docker build -t dynamic-gateway-operator:local -f operator/Dockerfile .

k3d image import dynamic-gateway:local -c dynamic-gateway-lab
k3d image import dynamic-gateway-operator:local -c dynamic-gateway-lab
```

---

## Fase 3 — CRD

```bash
kubectl apply -f k8s/crd/gatewayroutes.platform.saca.pags.yaml
kubectl get crd gatewayroutes.platform.saca.pags
```

---

## Fase 4 — Backend de teste (upstream da demo)

`go-httpbin` é só pra ter algo HTTP para o gateway encaminhar. Não faz parte
do POC.

```bash
kubectl apply -f k8s/samples/httpbin-in-cluster.yaml
kubectl rollout status deployment/go-httpbin -n platform
```

---

## Fase 5 — Service Account / RBAC / Service / Ingress

A RBAC do operator continua precisando ler `gatewayroutes` (CRD). Os
manifests de `k8s/operator/rbac.yaml` e `k8s/gateway/rbac.yaml` são
**neutros em relação ao backend** — funcionam tanto para ConfigMap quanto
para S3.

```bash
kubectl apply -f k8s/operator/service-account.yaml
kubectl apply -f k8s/operator/rbac.yaml
kubectl apply -f k8s/gateway/rbac.yaml
kubectl apply -f k8s/gateway/service.yaml
kubectl apply -f k8s/gateway/ingress.yaml
```

---

## Fase 6 — Criar o Secret com as credenciais AWS

O Secret é a única coisa que precisa existir antes dos Deployments S3, porque
ambos fazem `envFrom: secretRef: aws-credentials` e o kubelet não vai
agendar o pod sem o Secret.

```bash
kubectl create secret generic aws-credentials \
  --from-literal=AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY_ID \
  --from-literal=AWS_SECRET_ACCESS_KEY=$AWS_SECRET_ACCESS_KEY \
  -n platform

kubectl get secret aws-credentials -n platform
# NAME              TYPE     DATA   AGE
# aws-credentials   Opaque   2      ...
```

---

## Fase 7 — Editar overlay S3 com nome do bucket

Os manifests em `k8s/s3/*.yaml` trazem `REPLACE_WITH_BUCKET` como placeholder.
Substitua pelas suas variáveis:

```bash
sed -i "s|REPLACE_WITH_BUCKET|$S3_BUCKET|g" k8s/s3/gateway-deployment.yaml
sed -i "s|REPLACE_WITH_BUCKET|$S3_BUCKET|g" k8s/s3/operator-deployment.yaml

# Se sua região não é us-east-1, ajuste também:
sed -i "s|us-east-1|$S3_REGION|g" k8s/s3/gateway-deployment.yaml
sed -i "s|us-east-1|$S3_REGION|g" k8s/s3/operator-deployment.yaml
```

Confira que ficou ok:

```bash
grep -E '(BUCKET|REGION)' k8s/s3/gateway-deployment.yaml
```

---

## Fase 8 — Subir operator e gateway em modo S3

A ordem **importa**: o operator publica primeiro, o gateway lê depois.

```bash
# Operator primeiro — vai abrir o informer da CRD e, na primeira reconciliação,
# escrever um objeto vazio (sem rotas) em s3://$S3_BUCKET/snapshots/routes.yaml
kubectl apply -f k8s/s3/operator-deployment.yaml
kubectl rollout status deployment/gateway-route-operator -n platform

# Verifique que o objeto foi criado:
aws s3 ls s3://$S3_BUCKET/snapshots/
# 2026-...  ...  routes.yaml  <-- foi criado

aws s3 cp s3://$S3_BUCKET/snapshots/routes.yaml -
# version: <iso8601>
# generatedAt: <iso8601>
# routes: []
```

```bash
# Gateway depois — vai dar GetObject no S3 e bootar com routes vazio (ok,
# nada a expor).
kubectl apply -f k8s/s3/gateway-deployment.yaml
kubectl rollout status deployment/dynamic-gateway -n platform
```

Confirme nos logs:

```bash
# Operator
kubectl logs -n platform deployment/gateway-route-operator | grep -E "Published snapshot|Reconcile"

# Gateway
kubectl logs -n platform deployment/dynamic-gateway | grep "Loaded route config snapshot from s3"
```

---

## Fase 9 — Primeira rota: ciclo end-to-end via S3

Apply de uma `GatewayRoute` que aponta pro httpbin:

```bash
kubectl apply -f k8s/samples/route-httpbin.yaml
kubectl get gatewayroute -n platform
```

O ciclo esperado dentro de ~200ms (debounce do reconciler):

1. **Operator** detecta o evento na CRD, valida, monta snapshot novo
2. **Operator** faz `PutObject` em `s3://$S3_BUCKET/snapshots/routes.yaml`
3. **Operator** dispara HTTP best-effort: `POST /internal/routes/reload` no gateway
4. **Gateway** recebe o signal, dá `GetObject` no mesmo objeto S3, carrega a rota

Janela B (operator):
```
Reconcile id=N listed 1 GatewayRoutes (1 valid, 0 disabled, 0 invalid)
Published snapshot to s3://dynamic-gateway-routes-poc/snapshots/routes.yaml version=... routes=1
```

Janela C (gateway):
```
Loading routes from route config store
Loaded route config snapshot from s3://dynamic-gateway-routes-poc/snapshots/routes.yaml version=... routes=1
Route config snapshot loaded version=... routes=1
```

Janela D (s3 ls): o timestamp do `routes.yaml` atualizou.

Confirme inspecionando o objeto:

```bash
aws s3 cp s3://$S3_BUCKET/snapshots/routes.yaml -
# Deve listar a rota httpbin-route apontando para go-httpbin.platform.svc.cluster.local
```

E rotear tráfego:

```bash
kubectl port-forward -n platform svc/dynamic-gateway 8090:8080 &
sleep 2
curl -s http://localhost:8090/httpbin/get | python3 -m json.tool | head -20
# Deve voltar 200 e JSON com headers/url do go-httpbin
```

---

## Fase 10 — Editar uma rota em runtime

Mesma operação da Fase 9 do TESTE_LOCAL.md, mas agora a fonte da verdade é
o objeto S3. Usamos `kubectl patch` (não-interativo) ao invés de
`kubectl edit` pra deixar a demo reprodutível.

```bash
# Troca o path de /httpbin/** para /api/**
kubectl patch gatewayroute httpbin-route -n platform \
  --type=merge -p '{"spec":{"path":"/api/**"}}'

# Confirma o que ficou na CRD:
kubectl get gatewayroute httpbin-route -n platform -o jsonpath='{.spec.path}'; echo
# /api/**
```

Acompanhe nas janelas B e C — o operator deve loggar `Published snapshot
...routes=1`, o gateway `Loaded route config snapshot from s3://...`.

Verifique o S3:

```bash
aws s3api head-object --bucket $S3_BUCKET --key snapshots/routes.yaml \
  --query 'LastModified' --output text
# Timestamp recente (segundos atrás)

aws s3 cp s3://$S3_BUCKET/snapshots/routes.yaml - | grep -A1 path
# path: /api/**
```

Teste o roteamento novo:

kubectl port-forward -n platform svc/dynamic-gateway 8090:8080

```bash
curl -s -o /dev/null -w 'HTTP %{http_code}\n' http://localhost:8090/api/get
# HTTP 200

curl -s -o /dev/null -w 'HTTP %{http_code}\n' http://localhost:8090/httpbin/get
# HTTP 404 — o path antigo não está mais registrado
```

### Outras edições rápidas (variantes do mesmo patch)

```bash
# Voltar pro path original
kubectl patch gatewayroute httpbin-route -n platform \
  --type=merge -p '{"spec":{"path":"/httpbin/**"}}'

# Desabilitar a rota (operator vai publicar snapshot sem ela)
kubectl patch gatewayroute httpbin-route -n platform \
  --type=merge -p '{"spec":{"enabled":false}}'

# Trocar upstream
kubectl patch gatewayroute httpbin-route -n platform \
  --type=merge -p '{"spec":{"targetUri":"http://outro-servico.platform.svc.cluster.local:8080"}}'

# Restringir métodos
kubectl patch gatewayroute httpbin-route -n platform \
  --type=merge -p '{"spec":{"methods":["GET"]}}'
```

---

## Fase 11 — Registrar uma rota nova (sua própria API)

Demo de adicionar uma SEGUNDA rota ao gateway sem mexer nas existentes. Por
simplicidade reusamos o `go-httpbin` como upstream — o padrão vale para
qualquer API real (instruções de adaptação no final da fase).

### 11.1 — Criar o manifest da rota

`k8s/samples/route-hello.yaml`:

```yaml
apiVersion: platform.saca.pags/v1alpha1
kind: GatewayRoute
metadata:
  name: hello-route
  namespace: platform        # SEMPRE na ns do operator, não na ns da sua app
spec:
  path: /hello/**
  targetUri: http://go-httpbin.platform.svc.cluster.local:8080
  stripPrefix: 1
  methods:
    - GET
  enabled: true
```

Os 4 campos que importam:

| Campo         | O que faz                                                                              |
| ------------- | -------------------------------------------------------------------------------------- |
| `path`        | Predicate de match. `/hello` casa exato; `/hello/**` casa prefixo com sub-paths.       |
| `targetUri`   | Onde encaminhar. DNS interno: `<svc>.<ns>.svc.cluster.local:<porta>` ou URL externa.   |
| `stripPrefix` | Segmentos do path a remover antes de encaminhar. `0` mantém tudo; `1` tira o primeiro. |
| `methods`     | HTTP methods aceitos.                                                                  |

### 11.2 — Apply e observar a propagação

```bash
kubectl apply -f k8s/samples/route-hello.yaml
kubectl get gatewayroute -n platform
# Agora 2 rotas: a primeira (httpbin-route ou já editada para /api/**) + hello-route
```

Logs esperados em ~200 ms:

- **Janela B (operator):** `Reconcile id=N listed 2 GatewayRoutes` e
  `Published snapshot to s3://... routes=2`
- **Janela D (`aws s3 ls`):** `routes.yaml` com timestamp recente
- **Janela C (gateway):** `Loaded route config snapshot from s3://... routes=2`

Inspecione o snapshot:

```bash
aws s3 cp s3://$S3_BUCKET/snapshots/routes.yaml -
# Deve listar as duas rotas, incluindo hello-route com path /hello/**
```

### 11.3 — Tráfego pela rota nova

```bash
curl -s http://localhost:8090/hello/get | python3 -m json.tool | head -10
# Gateway recebe /hello/get -> tira /hello -> chama go-httpbin em /get
# Resposta JSON do go-httpbin com headers, args, url, etc.
```

### 11.4 — Adaptando para uma API real

Para expor sua própria API (em vez do go-httpbin), trocar apenas o `targetUri`
e ajustar `path`/`stripPrefix` ao formato dos endpoints expostos pela API:

| Cenário                                       | targetUri                                              | path / stripPrefix                            |
| --------------------------------------------- | ------------------------------------------------------ | --------------------------------------------- |
| Service no mesmo cluster (típico)             | `http://meu-app.apps.svc.cluster.local:8080`           | `/hello` + `stripPrefix: 0` (mantém /hello)   |
| Vários endpoints sob um prefixo do gateway    | `http://meu-app.apps.svc.cluster.local:8080`           | `/hello/**` + `stripPrefix: 1` (tira /hello)  |
| API externa fora do cluster                   | `https://api.exemplo.com`                              | conforme o que a API expõe                    |

Pré-requisitos para uma API real:
- Deployment + Service criados no cluster (em qualquer namespace; DNS interno
  funciona cross-namespace)
- A porta no `targetUri` precisa bater com a porta exposta pelo Service
- O gateway não precisa de RBAC extra — ele só faz HTTP outbound

> Observação: nenhuma das mudanças desta fase precisou tocar em **código**
> da plataforma. O ciclo `kubectl apply -> operator -> S3 -> gateway` é o
> mecanismo único de extensão.

---

## Fase 12 — Resiliência: matar o gateway

A pergunta central: se o gateway morrer e voltar, ele continua roteando sem
intervenção manual?

```bash
kubectl delete pod -n platform -l app.kubernetes.io/name=dynamic-gateway
# O Deployment recria um pod novo.

kubectl get pod -n platform -l app.kubernetes.io/name=dynamic-gateway -w
# Espere até ficar Running + Ready.
```

Olhe os logs do pod novo:

```bash
kubectl logs -n platform -l app.kubernetes.io/name=dynamic-gateway --tail=50 \
  | grep -E "Started GatewayApplication|Loaded route config snapshot"
# Started GatewayApplication in 0.0XXs
# Loaded route config snapshot from s3://.../snapshots/routes.yaml version=... routes=1
```

E confirme o tráfego sem reiniciar nada do operator:

```bash
# Mata o port-forward antigo (o pod antigo morreu)
pkill -f "port-forward.*dynamic-gateway"
kubectl port-forward -n platform svc/dynamic-gateway 8090:8080 &
sleep 2

curl -s -o /dev/null -w 'HTTP %{http_code}\n' http://localhost:8090/api/get
# HTTP 200 — gateway novo, mesmo snapshot do S3, mesmo roteamento
```

> Operador continua com `0` restarts. O gateway se ancora no snapshot,
> não no operator.

---

## Fase 13 — (Opcional) Voltar pro modo ConfigMap

Pra reverter sem destruir o cluster:

```bash
kubectl apply -f k8s/gateway/deployment.yaml
kubectl apply -f k8s/operator/deployment.yaml
kubectl rollout status deployment/gateway-route-operator -n platform
kubectl rollout status deployment/dynamic-gateway -n platform
```

O objeto em S3 fica intacto (até alguém apagar). Na próxima reconciliação o
operator passa a escrever de novo na ConfigMap `gateway-routes`.

---

## Encerramento

> **A camada de armazenamento foi abstraída atrás de duas interfaces de uma
> linha cada (`RouteConfigPublisher` e `RouteConfigProvider`). O mesmo
> binário, com `*_STORE_TYPE=s3` em vez de `configmap`, troca a "memória"
> do plano de controle de uma ConfigMap para um objeto S3 — sem mexer em
> nenhuma classe do pipeline de reconciliação ou roteamento.**

---

## Cleanup

```bash
# Cluster:
./scripts/delete-cluster.sh

# Bucket (opcional, se quiser deixar limpo):
aws s3 rm s3://$S3_BUCKET/snapshots/ --recursive

# Secret no shell (não esquece):
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY
```
