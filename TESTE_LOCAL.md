# Teste local — roteiro passo a passo

Roteiro de demonstração da POC focado nos três componentes do projeto: **CRD `GatewayRoute`**, **operator** e **gateway**. Todo o resto (k3d, kubectl, namespace, backend de teste) é infraestrutura de apoio para subir esses três no Kubernetes.

A ideia é fazer a demo do zero, sem nada no cluster, e ir mostrando o efeito de cada peça à medida que ela entra em cena. Cada fase abaixo é **executada na ordem**, e cada fase deixa o estado pronto para a próxima.

## Pré-requisitos

- `docker` (com permissão de rodar containers — Docker Desktop ou daemon nativo)
- `k3d` 5.x
- `kubectl` 1.30+
- **GraalVM 25** (Oracle GraalVM 25 ou GraalVM Community 25). Verifique com `java --version` — espera-se algo como `Oracle GraalVM 25.0.3+9-LTS`. Caminho típico via SDKMAN: `~/.sdkman/candidates/java/25.0.3-graal`.
- `zlib1g-dev` e `build-essential` (instale com `sudo apt install -y zlib1g-dev build-essential`) — o linker do native-image precisa de `libz` e do `gcc`.
- `python3` (para formatar JSON com `python3 -m json.tool`)
- Porta `8090` livre no host

> Se o `docker pull` falhar com `gpg: decryption failed: No secret key`, remova o bloco `"credsStore": "desktop"` do `~/.docker/config.json` (faça backup antes).

## Setup de janelas

Use 3 terminais lado a lado. Em **todos**:

```bash
export PATH="/usr/local/bin:$HOME/.local/bin:$PATH"
cd ~/Projetos/dynamic-gateway-control-plane
```

- **Terminal A** — onde você dirige a demo (apply, edit, delete).
- **Terminal B** — observação contínua (`kubectl logs -f`). Comece vazio; vai abrir o tail quando o operator subir.
- **Terminal C** — `kubectl port-forward` para acessar o gateway de fora do cluster. Comece vazio; vai abrir quando o gateway subir.

## Sobre o upstream da demo

Na visão do gateway/proxy existem dois lados:

```
   [cliente]  ───→  [gateway]  ───→  [upstream / backend / origem]
   "downstream"                       "upstream"
```

- **Downstream**: quem chama o gateway (o cliente, o browser, o curl).
- **Upstream** (também chamado de *backend*, *origem* ou *target*): o serviço para onde o gateway encaminha o tráfego.

No `GatewayRoute`, o campo `spec.targetUri` aponta para o **upstream**. Esse upstream **não faz parte deste POC** — em produção, ele seria um serviço interno do cluster (`auth-service.payments.svc.cluster.local:8080`, por exemplo). Para a demo vamos subir um `go-httpbin` dentro do cluster (Fase 4) que faz o papel desse upstream, assim a demo é determinística e não depende de internet externa.

---

## Fase 0 — Tear down completo

Comece do zero. Sem cluster, sem CRD, sem deployments. Se for a primeiríssima vez (cluster nunca criado), esse comando vai falhar silenciosamente — pode ignorar.

```bash
./scripts/delete-cluster.sh
```

> "Cluster zero. Vou subir do nada para mostrar a arquitetura."

---

## Fase 1 — Cluster e namespace (infra básica)

```bash
HOST_PORT=8090 ./scripts/create-cluster.sh
```

⚠️ **Atualizar o `~/.kube/config`** — sempre que o cluster é recriado, a porta do API server muda. Sem esse passo, o próximo `kubectl` falha com `connection refused`:

```bash
k3d kubeconfig merge dynamic-gateway-lab \
    --kubeconfig-merge-default --kubeconfig-switch-context
```

Confirme:

```bash
kubectl config current-context        # deve mostrar k3d-dynamic-gateway-lab
kubectl get ns
kubectl get crd | grep saca || echo "Nenhum CRD do projeto."
kubectl get all -n platform
```

> "Cluster vazio. Namespace `platform` criado. O Kubernetes nem sabe o que é `GatewayRoute` ainda."

---

## Fase 2 — Build dos binários nativos (GraalVM AOT) e das imagens

Esse passo é "infra de build" — gateway e operator são compilados como **executáveis nativos** via GraalVM Native Image. Sem JVM/JRE na imagem final, startup em ~400ms em vez de ~5s, e ~40% menos memória em runtime.

| Passo                                     | Tempo típico   |
|-------------------------------------------|----------------|
| 2.1 — `nativeCompile` do gateway          | ~55-65s        |
| 2.1 — `nativeCompile` do operator         | ~40-50s        |
| 2.2 — `docker build` (só copia o binário) | ~10s cada      |
| 2.3 — `crictl pull` do upstream           | ~5s            |
| **Total a frio**                          | **~3 minutos** |

### 2.1 — Compilar os binários nativos localmente

Garanta que o `JAVA_HOME` aponta para GraalVM 25:

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/25.0.3-graal
export PATH=$JAVA_HOME/bin:$PATH
java --version    # deve mostrar "Oracle GraalVM 25.0.3..."
```

Compile:

```bash
./gradlew :gateway:nativeCompile :operator:nativeCompile -x test
```

Confirme que os binários foram gerados:

```bash
ls -lh gateway/build/native/nativeCompile/dynamic-gateway \
       operator/build/native/nativeCompile/dynamic-gateway-operator
file gateway/build/native/nativeCompile/dynamic-gateway
# espera-se "ELF 64-bit LSB pie executable, x86-64, ... dynamically linked"
```

> Smoke test opcional (sem cluster): `KUBECONFIG=/tmp/nonexistent gateway/build/native/nativeCompile/dynamic-gateway` deve imprimir `Started GatewayApplication in 0.388 seconds (process running for 0.39)` em ~400ms e então falhar tentando contactar o K8s (esperado fora do cluster).

### 2.2 — Empacotar em imagens Docker (camada fina sobre `debian:stable-slim`)

```bash
docker build -f gateway/Dockerfile  -t dynamic-gateway:local           .
docker build -f operator/Dockerfile -t dynamic-gateway-operator:local  .
```

Os `Dockerfile`s **não recompilam** — eles só copiam os binários nativos que você gerou na 2.1 para uma imagem `debian:stable-slim` com `ca-certificates`. Resultado: ~361 MiB (gateway) e ~271 MiB (operator) — vs ~439/374 MiB nas versões JVM.

### 2.3 — Importar as imagens locais para dentro do cluster

```bash
k3d image import \
    dynamic-gateway:local \
    dynamic-gateway-operator:local \
    -c dynamic-gateway-lab
```

> Use `k3d image import` **só para imagens que você buildou localmente**. Para imagens públicas multi-arch (como `mccutchen/go-httpbin`), o `k3d image import` pode falhar com `tr: content digest sha256:... not found` — porque o Docker Hub guarda um *manifest list* multi-arch e o `docker pull` só baixou os layers da sua arquitetura. Para públicas, prefira o **2.4** abaixo.

### 2.4 — Baixar a imagem do upstream direto no cluster (single-arch garantido)

```bash
docker exec k3d-dynamic-gateway-lab-server-0 \
    crictl pull mccutchen/go-httpbin:latest
```

`crictl pull` instrui o containerd do próprio node a baixar a imagem do registry — ele resolve a arquitetura correta sozinho.

### 2.5 — Confirmar que as 3 imagens estão dentro do cluster

```bash
docker exec k3d-dynamic-gateway-lab-server-0 \
    ctr -n k8s.io image list | grep -E "(dynamic-gateway|httpbin)"
```

Esperado: 3 linhas com `dynamic-gateway:local`, `dynamic-gateway-operator:local`, `mccutchen/go-httpbin:latest`.

> "Imagens prontas. Daqui em diante são só `kubectl apply` — sem builds no meio. E o startup vai mostrar a diferença do nativo: o gateway vai ficar Ready em segundos, não em minuto e meio."

### Troubleshooting

#### "cannot find -lz" no nativeCompile

Falta libz de desenvolvimento. Instale:
```bash
sudo apt install -y zlib1g-dev build-essential
```

#### "GraalVM JDK not found" ou usa o JDK errado

Verifique `java --version` — precisa mostrar "Oracle GraalVM" ou "GraalVM Community". Se não, ajuste o `JAVA_HOME`.

#### "k3d image import" falha com hash não encontrado

```bash
# Importa via tarball local (força single-arch a partir do que está no Docker)
for img in dynamic-gateway:local dynamic-gateway-operator:local; do
    safe=$(echo "$img" | tr '/:' '__')
    docker save "$img" -o "/tmp/${safe}.tar"
    k3d image import "/tmp/${safe}.tar" -c dynamic-gateway-lab
done
```

---

## Fase 3 — Registrar o CRD (1º componente do POC)

Aplique o CRD — esse é o passo que **ensina ao Kubernetes** o tipo `GatewayRoute`:

```bash
kubectl apply -f k8s/crd/gatewayroutes.platform.saca.pags.yaml
kubectl get crd gatewayroutes.platform.saca.pags
kubectl explain gatewayroute.spec
```

> "Acabei de registrar a `GatewayRoute` como um tipo de primeira classe no Kubernetes. Tem schema, validação, printer columns. Mas continua sem ninguém observando — é só uma definição de tipo."

Aplique a primeira rota — o tipo agora existe, então o `apply` é aceito, mas a rota fica **inerte** (no etcd, sem efeito):

```bash
kubectl apply -f k8s/samples/route-httpbin.yaml
kubectl get gatewayroutes -n platform
```

> "A rota está no etcd. Mas como ninguém escuta, ela ainda não vira nada. Repare: o `targetUri` aponta para `http://go-httpbin.platform.svc.cluster.local:8080` — esse service nem existe ainda."

### Demonstração opcional: "sem CRD nada funciona"

Se quiser mostrar didaticamente que sem o CRD o `kubectl apply` é rejeitado, você precisaria ter feito isso **antes** do `kubectl apply -f k8s/crd/...` acima. Salve para uma próxima apresentação ou pule esta caixa.

```bash
# (Só faz sentido se você ainda NÃO aplicou o CRD nesta sessão)
# kubectl apply -f k8s/samples/route-httpbin.yaml
# → "error: resource mapping not found ... no matches for kind GatewayRoute"
```

---

## Fase 4 — Subir o backend de teste (upstream da demo)

> Esta fase **não é parte do POC** — `CRD + operator + gateway` continuam sendo os únicos componentes do projeto. Aqui estamos apenas subindo um serviço HTTP qualquer para o gateway ter um upstream pra onde encaminhar. Em produção, esse `targetUri` apontaria para serviços reais da plataforma (`auth-service`, `payments-api`, etc.).

```bash
kubectl apply -f k8s/samples/httpbin-in-cluster.yaml
kubectl rollout status deployment/go-httpbin -n platform --timeout=60s
```

Confirme:

```bash
kubectl get deployment,service -n platform
```

Valide o backend respondendo dentro do cluster (de um pod efêmero):

```bash
kubectl run -it --rm curl --image=curlimages/curl --restart=Never -- \
    curl -s http://go-httpbin.platform.svc.cluster.local:8080/get
```

> "Backend de mentirinha. Responde a `/get`, `/post`, `/anything/*`, etc., ecoando o request. Existe só dentro do cluster — **não tem como chegar nele de fora**. É exatamente isso que o gateway vai resolver."

---

## Fase 5 — Subir o gateway (2º componente do POC, sozinho)

```bash
kubectl apply -f k8s/gateway/rbac.yaml
kubectl apply -f k8s/gateway/deployment.yaml
kubectl apply -f k8s/gateway/service.yaml
kubectl rollout status deployment/dynamic-gateway -n platform --timeout=180s
```

> "Reparem que o rollout terminou em segundos, não no minuto-e-meio que seria com JVM. O binário nativo do gateway boota em ~400ms — o tempo dominante agora é o readinessProbe (`initialDelaySeconds: 5`)."

Em **Terminal C** abra o port-forward (deixe rodando o resto da demo):

```bash
kubectl port-forward -n platform svc/dynamic-gateway 8080:8080
```

> Atenção: se o pod do gateway for reiniciado (Fase 10), o port-forward morre e precisa ser reaberto.

Em **Terminal A** verifique que o gateway está vivo, mas sem rotas:

```bash
curl -s http://localhost:8080/actuator/health  | python3 -m json.tool
curl -s http://localhost:8080/internal/routes  | python3 -m json.tool
```

Mostre o log do bootstrap:

```bash
kubectl logs -n platform deployment/dynamic-gateway | grep -E "(ConfigMap|snapshot|bootstrap)"
```

Esperado:

```
ConfigMap platform/gateway-routes not found; returning empty snapshot
Gateway bootstrap completed activeRoutes=0
```

> "Ele tentou ler o ConfigMap consolidado, não achou, e bootou com lista vazia — sem crashar. O `GatewayRoute` que apliquei na Fase 3 está no etcd, o `go-httpbin` da Fase 4 está vivo, e o gateway não sabe que nem um, nem outro existem. Está faltando a peça que costura o CRD ao gateway."

Confirme que a rota ainda não funciona — **execute este curl ANTES de avançar para a Fase 6**, porque a partir do momento em que o operator subir, esse mesmo comando passa a retornar `HTTP 200`:

```bash
curl -s -o /dev/null -w "HTTP %{http_code}\n" http://localhost:8080/httpbin/get
# HTTP 404 — não tem rota carregada (gateway está sozinho, sem operator)
```

> Se você já passou da Fase 6 e voltou aqui, esse curl vai retornar 200 — porque o operator já populou o ConfigMap e o gateway já tem as rotas em memória. Isso é correto. O `404` aqui é uma "foto" do estado **antes** do operator entrar em cena.

---

## Fase 6 — Subir o operator (3º componente do POC, fecha o circuito)

```bash
kubectl apply -f k8s/operator/service-account.yaml
kubectl apply -f k8s/operator/rbac.yaml
kubectl apply -f k8s/operator/deployment.yaml
kubectl rollout status deployment/gateway-route-operator -n platform --timeout=120s
```

> Mesma história do gateway: o operator nativo sobe em ~500ms. Antes do log `Reconcile started id=1` chegar, a JVM já daria tempo de carregar metade dos classloaders.

Em **Terminal B** (deixe rodando o resto da demo):

```bash
kubectl logs -n platform deployment/gateway-route-operator -f
```

Esperado nos logs em ~2 segundos:

```
GatewayRoute informer started namespace=platform
Reconcile started id=1 routes=1
Reconcile id=1 listed 1 GatewayRoutes (1 valid, 0 disabled, 0 invalid)
Reconcile id=1 snapshot built version=... entries=1
Created ConfigMap platform/gateway-routes with snapshot version=... routes=1
Reconcile id=1 snapshot published to backend
Reload signal accepted url=http://dynamic-gateway... status=200
Reconcile id=1 reload signal sent
```

> "O operator subiu, listou os `GatewayRoute` no namespace, achou o que apliquei na Fase 3, validou, montou um snapshot consolidado, escreveu o ConfigMap `gateway-routes` e avisou o gateway por HTTP `POST /internal/routes/reload`."

Em **Terminal A** verifique o efeito:

```bash
# 1) ConfigMap acabou de aparecer (não existia antes da Fase 6)
kubectl get configmap gateway-routes -n platform -o jsonpath='{.data.routes\.yaml}'; echo

# 2) Rotas em memória do gateway
curl -s http://localhost:8080/internal/routes | python3 -m json.tool

# 3) Atravessar o gateway até o go-httpbin (deve retornar 200 com JSON)
curl -s http://localhost:8080/httpbin/get | python3 -m json.tool
```

No JSON de resposta do último curl, repare no campo `url`: **deveria mostrar `/get`, não `/httpbin/get`**. Esse é o `StripPrefix=1` em ação — o gateway tirou o primeiro segmento (`/httpbin`) antes de encaminhar.

---

## Fase 7 — Aplicar uma segunda rota (sem restart)

```bash
cat > /tmp/route-anything.yaml <<'EOF'
apiVersion: platform.saca.pags/v1alpha1
kind: GatewayRoute
metadata:
  name: anything-route
  namespace: platform
spec:
  path: /anything/**
  targetUri: http://go-httpbin.platform.svc.cluster.local:8080
  stripPrefix: 0
  methods:
    - GET
  enabled: true
EOF

kubectl apply -f /tmp/route-anything.yaml
```

**Terminal B** vai logar em ~250ms (a janela de debounce do operator):

```
GatewayRoute added name=anything-route
Reconcile started id=2 routes=2
Reconcile id=2 listed 2 GatewayRoutes (2 valid, 0 disabled, 0 invalid)
Updated ConfigMap ... routes=2
Reload signal accepted ... status=200
```

Em **Terminal A**:

```bash
kubectl get gatewayroutes -n platform
kubectl get configmap gateway-routes -n platform -o jsonpath='{.data.routes\.yaml}'; echo
curl -s http://localhost:8080/internal/routes | python3 -m json.tool

# A segunda rota usa stripPrefix=0, então o path inteiro chega no upstream
curl -s http://localhost:8080/anything/qualquer/coisa | python3 -m json.tool

kubectl get pods -n platform
```

> "Sem restart. Duas rotas convivem. ConfigMap reescrito com o snapshot completo. Coluna `RESTARTS` continua zero em ambos os pods. Repare que essa segunda rota usa `stripPrefix: 0`, então o go-httpbin ecoa de volta `/anything/qualquer/coisa` no campo `url`."

---

## Fase 8 — Filtragem por validação (defesa em camadas)

Aplique duas rotas problemáticas:

```bash
kubectl apply -f k8s/samples/route-disabled.yaml
kubectl apply -f k8s/samples/route-invalid.yaml
```

**Terminal B** vai logar:

```
GatewayRoute added name=disabled-route
GatewayRoute added name=invalid-route
Reconcile started id=3 routes=4
WARN GatewayRoute invalid-route is invalid: spec.targetUri is required
Reconcile id=3 listed 4 GatewayRoutes (2 valid, 1 disabled, 1 invalid)
Updated ConfigMap ... routes=2
```

**Terminal A**:

```bash
kubectl get gatewayroutes -n platform
# Lista 4 rotas (todas existem no etcd)

curl -s http://localhost:8080/internal/routes | python3 -m json.tool
# Mas o gateway só serve 2 — as duas válidas e habilitadas
```

> "Defesa em duas camadas: o CRD tem schema OpenAPI que rejeita YAMLs grosseiros antes do operator ver; o operator faz a validação semântica e pegou a `disabled-route` (enabled=false) e a `invalid-route` (targetUri em branco). O gateway só recebe o que está limpo — ele nem fica sabendo que essas duas existem."

---

## Fase 9 — Editar uma rota em runtime

Mude o `stripPrefix` do `httpbin-route` de `1` para `2`:

```bash
kubectl patch gatewayroute httpbin-route -n platform \
    --type=merge -p '{"spec":{"stripPrefix":2}}'
sleep 3
curl -s http://localhost:8080/internal/routes | python3 -m json.tool
# StripPrefix=2 agora
```

Tente o curl: com `stripPrefix=2`, o gateway corta dois segmentos antes de encaminhar — `/httpbin/get` vira `/`, e o go-httpbin não atende `/`:

```bash
curl -s -o /dev/null -w "HTTP %{http_code}\n" http://localhost:8080/httpbin/get
# 404 do go-httpbin
```

Reverta:

```bash
kubectl patch gatewayroute httpbin-route -n platform \
    --type=merge -p '{"spec":{"stripPrefix":1}}'
sleep 3
curl -s -o /dev/null -w "HTTP %{http_code}\n" http://localhost:8080/httpbin/get
# HTTP 200 de novo
```

> "Reload em runtime. Mesmo fluxo: operator vê o patch, regenera o snapshot, sinaliza o gateway. Sem restart. E o efeito é imediato — vocês acabaram de ver o comportamento de roteamento mudar e voltar em segundos."

---

## Fase 10 — A pergunta principal: e se o gateway morrer?

Mostre o estado atual:

```bash
kubectl get pods -n platform
# RESTARTS=0 em ambos os deployments
```

Mate o pod do gateway (rolling restart):

```bash
kubectl rollout restart deployment/dynamic-gateway -n platform
kubectl rollout status  deployment/dynamic-gateway -n platform
```

⚠️ O `port-forward` no **Terminal C** morre com o pod antigo. Reabra:

```bash
kubectl port-forward -n platform svc/dynamic-gateway 8080:8080
```

Em **Terminal A**, mostre o log da startup do pod novo:

```bash
kubectl logs -n platform deployment/dynamic-gateway | grep -E "(snapshot|Routes replaced|bootstrap)"
```

Esperado:

```
Loading routes from route config store
Loaded route config snapshot version=... routes=2
Route loaded id=anything-route path=/anything/** ...
Route loaded id=httpbin-route path=/httpbin/** ...
Routes replaced in memory count=2
RefreshRoutesEvent published
Gateway bootstrap completed activeRoutes=2
```

E prove que tudo voltou a funcionar:

```bash
curl -s http://localhost:8080/internal/routes | python3 -m json.tool
# As 2 rotas válidas estão lá

curl -s http://localhost:8080/httpbin/get | python3 -m json.tool
# HTTP 200 — gateway voltou a rotear
```

> "O pod foi destruído. Subiu zerado, sem nenhuma rota em memória. Mas o ConfigMap continuava lá — fonte de verdade do estado materializado — e o Startup do gateway lê dele, popula o repositório em memória e dispara o `RefreshRoutesEvent`. **Sem o operator participar nesse intervalo.** Se o operator também tivesse caído junto, o gateway ainda recuperaria as rotas existentes."

Reforce:

```bash
kubectl get deployment -n platform
# Operator continua com 0 restarts. Ele não foi tocado.
```

---

## Fase 11 — Trocar o backend para S3 (AWS, IAM User)

Esta fase NÃO faz parte da demo de operator/CRD/gateway. Ela demonstra que a
camada de armazenamento foi abstraída: trocando uma variável de ambiente, o
mesmo binário passa a ler/escrever o snapshot em S3 ao invés de ConfigMap.

> Pré-requisitos:
> - Um bucket S3 já criado (ex.: `dynamic-gateway-routes-poc`).
> - Um IAM User com acesso ao bucket. Policy mínima:
>   ```json
>   {
>     "Version": "2012-10-17",
>     "Statement": [
>       {
>         "Effect": "Allow",
>         "Action": ["s3:GetObject", "s3:PutObject"],
>         "Resource": "arn:aws:s3:::dynamic-gateway-routes-poc/snapshots/*"
>       }
>     ]
>   }
>   ```
> - As credenciais do IAM User exportadas no shell antes de começar:
>   ```bash
>   export AWS_ACCESS_KEY_ID=AKIA...
>   export AWS_SECRET_ACCESS_KEY=...
>   export S3_BUCKET=dynamic-gateway-routes-poc
>   export S3_REGION=us-east-1
>   ```

### 11.1 — Criar o Secret com as credenciais

```bash
kubectl create secret generic aws-credentials \
  --from-literal=AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY_ID \
  --from-literal=AWS_SECRET_ACCESS_KEY=$AWS_SECRET_ACCESS_KEY \
  -n platform

kubectl get secret aws-credentials -n platform
```

### 11.2 — Editar os manifests S3 com o nome do bucket

Os arquivos em `k8s/s3/` trazem `REPLACE_WITH_BUCKET`. Substitua antes de
aplicar (a região default é `us-east-1`; troque se for outra):

```bash
sed -i "s|REPLACE_WITH_BUCKET|$S3_BUCKET|g" k8s/s3/gateway-deployment.yaml
sed -i "s|REPLACE_WITH_BUCKET|$S3_BUCKET|g" k8s/s3/operator-deployment.yaml
```

### 11.3 — Aplicar os Deployments do modo S3

Os manifests do diretório `k8s/s3/` SUBSTITUEM os Deployments existentes
(mesmo nome e namespace). O Kubernetes vai fazer rollout dos dois pods.

```bash
kubectl apply -f k8s/s3/gateway-deployment.yaml
kubectl apply -f k8s/s3/operator-deployment.yaml

kubectl rollout status deployment/dynamic-gateway -n platform
kubectl rollout status deployment/gateway-route-operator -n platform
```

### 11.4 — Esperar o operator publicar o snapshot em S3

A primeira reconciliação dispara o `PutObject` em
`s3://$S3_BUCKET/snapshots/routes.yaml`. Verifique no log:

```bash
kubectl logs -n platform deployment/gateway-route-operator | grep "Published snapshot"
# Esperado: Published snapshot to s3://<bucket>/snapshots/routes.yaml version=... routes=N
```

E no próprio S3:

```bash
aws s3 ls s3://$S3_BUCKET/snapshots/
aws s3 cp s3://$S3_BUCKET/snapshots/routes.yaml -
```

### 11.5 — Verificar o gateway carregando do S3

```bash
kubectl logs -n platform deployment/dynamic-gateway | grep "Loaded route config snapshot from s3"
# Esperado: Loaded route config snapshot from s3://<bucket>/snapshots/routes.yaml version=... routes=N
```

Roteie tráfego pra confirmar:

```bash
kubectl port-forward -n platform svc/dynamic-gateway 8090:8080 &
curl -s http://localhost:8090/httpbin/get | python3 -m json.tool | head
```

### 11.6 — Alterar uma rota e ver o ciclo via S3

Mesma operação da Fase 9, mas agora a fonte da verdade é o objeto no S3:

```bash
kubectl edit gatewayroute httpbin-route -n platform
# (mude algo, salve)

# Operator detecta, valida, escreve novo objeto em S3 e dispara HTTP signal
# Gateway recebe signal, faz GetObject, atualiza in-memory routes
```

Confirme com:

```bash
aws s3api head-object --bucket $S3_BUCKET --key snapshots/routes.yaml \
  --query 'LastModified' --output text
# Deve mostrar timestamp recente
```

### 11.7 — Voltar pro modo ConfigMap (opcional)

```bash
kubectl apply -f k8s/gateway/deployment.yaml
kubectl apply -f k8s/operator/deployment.yaml
```

A ConfigMap `gateway-routes` continua intacta — o operator volta a escrever
nela na próxima reconciliação.

---

## Encerramento

Resumo de uma frase para fechar a apresentação:

> **O operator é o controle. O ConfigMap (ou S3) é a memória. O gateway é stateless em relação ao operator, mas se ancora no snapshot.**

---

## Cleanup (opcional)

```bash
./scripts/delete-cluster.sh
```
