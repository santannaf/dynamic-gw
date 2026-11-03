# Políticas de rota: guia de uso

Cada rota pode declarar, além do `access`, cinco políticas: `rateLimit`, `throttle`, `filters`, `transform` e `aggregate`. Elas ficam na própria rota, no `GatewayRoute` (v2) e nas rotas do properties (v1), com os mesmos nomes. Os tetos que valem para todas as rotas juntas ficam em `gateway.traffic.*`, no `application.yaml` do gateway. O trabalho está na história ETT-988.

| Quero...                                                          | Use                                    | Quando barra               |
|-------------------------------------------------------------------|----------------------------------------|----------------------------|
| limitar quantas chamadas a rota, ou cada cliente, faz por período | [`rateLimit`](#1-rate-limit)           | 429, com `Retry-After`     |
| proteger um backend lento de muitas chamadas ao mesmo tempo       | [`throttle`](#2-throttling)            | 503, com `Retry-After`     |
| mexer em header, path, query ou tamanho do corpo                  | [`filters`](#3-filtros)                | 413 (`RequestSize`)        |
| enxugar, renomear ou embrulhar os campos da resposta JSON         | [`transform`](#4-transformação)        | —                          |
| montar uma resposta só a partir de vários backends                | [`aggregate`](#5-agregação)            | 502 ou 504, se todos falham |

**Sumário:** [Como declarar](#como-declarar) · [1. Rate limit](#1-rate-limit) · [2. Throttling](#2-throttling) · [3. Filtros](#3-filtros) · [4. Transformação](#4-transformação) · [5. Agregação](#5-agregação) · [Contra uma API de verdade](#contra-uma-api-de-verdade-jsonplaceholder) · [Combinando políticas](#combinando-políticas) · [Tetos globais](#tetos-globais) · [Auditoria](#auditoria) · [Problemas comuns](#problemas-comuns) · [Onde cada coisa roda](#onde-cada-coisa-roda)

---

## Como declarar

### v2: no `GatewayRoute`

```yaml
apiVersion: platform.saca.pags/v1alpha1
kind: GatewayRoute
metadata:
  name: pedidos
  namespace: platform
spec:
  path: /pedidos/**
  targetUri: http://pedidos.pedidos.svc.cluster.local:8080
  stripPrefix: 1
  team: time-pedidos
  access:
    anyOf: [group:pedidos-leitura]
  rateLimit:
    requests: 60
    period: 1m
    key: user
```

Depois do `kubectl apply`, todas as réplicas passam a servir a rota com a política em segundos. O API server recusa na hora o que o schema do CRD pega (número fora do intervalo, filtro fora do catálogo, `allow` junto com `deny`). O resto, o operator confere antes de publicar: rota inválida fica fora do snapshot (veja [Problemas comuns](#problemas-comuns)).

### v1: no `application.yaml`

```yaml
gateway:
  routes:
    store:
      type: properties
      properties:
        routes:
          - id: pedidos
            path: /pedidos/**
            target-uri: http://pedidos.pedidos.svc.cluster.local:8080
            strip-prefix: 1
            team: time-pedidos
            access:
              any-of: [group:pedidos-leitura]
            rate-limit:
              requests: 60
              period: 1m
              key: user
```

No v1, mudar a rota é gerar a imagem de novo. Rota inválida não é servida: o gateway loga o problema e sobe com as outras.

### Os nomes nos dois modos

| v2 (`GatewayRoute.spec`)                         | v1 (properties)                                        |
|--------------------------------------------------|--------------------------------------------------------|
| `rateLimit`                                      | `rate-limit`                                           |
| `throttle.maxConcurrent`, `maxQueue`, `maxWait`  | `throttle.max-concurrent`, `max-queue`, `max-wait`     |
| `filters[].name` e `filters[].args`              | iguais: `args` é um mapa repassado ao filtro, então é `maxSize`, não `max-size` |
| `transform` e `aggregate`                        | iguais (os campos são de uma palavra só)               |

### Conferir o que está ativo

`GET /internal/routes` lista cada rota com as políticas que o gateway está aplicando. Com SSO, ele exige `role:gateway-admin` ou `group:gateway-admins` (veja no README como pegar o token). Trecho da resposta:

```json
{
  "id": "policy-per-client",
  "uri": "http://go-httpbin.platform.svc.cluster.local:8080",
  "predicates": ["Path=/pol/per-client/**"],
  "filters": ["StripPrefix=2"],
  "access": {"mode": "public"},
  "rateLimit": {"requests": 2, "period": "10s", "burst": null, "key": "header:X-Api-Key"}
}
```

### A ordem em cada requisição

```
access → rate limit → throttle → filtros da rota → proxy ou agregação → transformação da resposta
```

- Quem o `access` barra (401, 403 ou o redirect para o login) não gasta rate limit nem ocupa vaga de throttle.
- Quem o rate limit barra (429) não ocupa vaga de throttle.
- A transformação vale tanto para a resposta do backend quanto para a resposta montada pela agregação.

### Os limites contam por réplica

Rate limit e throttling ficam em memória, em cada pod do gateway, como no KrakenD Community. Não há Redis. Com 3 réplicas atrás do nginx-lb, uma rota com `requests: 100` aceita até 300 requisições por período no total. Divida o teto desejado pelo número de réplicas, ou fixe as réplicas.

### Experimente no lab

As rotas de exemplo estão em [`k8s/samples/routes-policies.yaml`](k8s/samples/routes-policies.yaml) (v2) e no fim do [`application-standalone.yaml`](gateway/src/main/resources/application-standalone.yaml) (v1), com as mesmas regras. Elas apontam para o go-httpbin, que devolve o que recebeu, e são públicas, então não pedem login. As respostas mostradas neste guia saíram delas.

```bash
GW=http://localhost:8080                            # ou a porta do HOST_PORT do seu cluster
kubectl apply -f k8s/samples/routes-policies.yaml   # v2; no v1 (make standalone-up) elas já vêm na imagem
kubectl -n platform scale deployment/dynamic-gateway --replicas=1   # uma réplica: contas exatas

# no fim
kubectl delete -f k8s/samples/routes-policies.yaml
kubectl -n platform scale deployment/dynamic-gateway --replicas=2
```

`make policies-test` faz tudo isso e confere cada política, no v1 e no v2.

---

## 1. Rate limit

Conta as requisições de um período e recusa o excesso com 429. Serve para dividir a capacidade de um backend entre os clientes, conter abuso e cumprir cota de API. O limite vale para a rota toda (todos juntos) ou para cada cliente.

```yaml
# v2 (GatewayRoute.spec)              # v1 (properties)
rateLimit:                            rate-limit:
  requests: 100                         requests: 100
  period: 1s                            period: 1s
  burst: 20                             burst: 20
  key: ip                               key: ip
```

| Campo      | Obrigatório | Default    | Aceita                                                         |
|------------|-------------|------------|----------------------------------------------------------------|
| `requests` | sim         | —          | inteiro ≥ 1: requisições por período                           |
| `period`   | sim         | —          | número seguido de `ms`, `s`, `m` ou `h`: `500ms`, `10s`, `1m`  |
| `burst`    | não         | `requests` | inteiro ≥ 1: quantas podem chegar de uma vez                   |
| `key`      | não         | `route`    | `route`, `ip`, `user`, `client-id` ou `header:<nome>`          |

### De quem é o orçamento (`key`)

| `key`           | Um orçamento para                                               | Use quando                                     |
|-----------------|-----------------------------------------------------------------|------------------------------------------------|
| `route`         | todos os clientes juntos                                        | o limite é do backend, e não de quem chama     |
| `ip`            | cada endereço de cliente                                        | a rota é pública ou não tem identidade         |
| `user`          | cada usuário autenticado (o `X-Auth-Request-Subject`)           | a rota exige login e o limite é por pessoa     |
| `client-id`     | cada aplicação cliente (o `X-Auth-Request-Client-Id`, do token) | sistemas chamam a rota com token OAuth         |
| `header:<nome>` | cada valor do header                                            | o cliente se identifica por header, como uma API key |

- Sem identidade, ou sem o header, o cliente cai no orçamento do seu IP. Ele nunca divide um orçamento só com todos os anônimos.
- Do valor do header, só o hash fica em memória, porque ele costuma ser uma API key.
- `user` e `client-id` dependem do `access` com login, e o rate limit roda depois dele.

### Período e burst

O algoritmo é o GCRA, uma variante de token bucket sem janela fixa. As requisições ficam espaçadas `period / requests` umas das outras, e até `burst` podem chegar de uma vez. Com `requests: 5` e `period: 10s`, cabem 5 de uma vez, e o orçamento devolve uma vaga a cada 2 segundos. Não existe a virada de minuto em que o limite zera de uma vez e deixa passar o dobro.

`requests: 600`, `period: 1m` e `burst: 50` dão 600 por minuto, no ritmo de uma a cada 100 ms, com picos de até 50 de uma vez.

### O que o cliente recebe

Rota `policy-limited` (`requests: 5`, `period: 10s`), seis chamadas seguidas:

```
# 1ª chamada
HTTP/1.1 200 OK
Ratelimit-Limit: 5
Ratelimit-Policy: 5;w=10
Ratelimit-Remaining: 4
Ratelimit-Reset: 2

# 5ª chamada
HTTP/1.1 200 OK
Ratelimit-Limit: 5
Ratelimit-Policy: 5;w=10
Ratelimit-Remaining: 0
Ratelimit-Reset: 10

# 6ª chamada
HTTP/1.1 429 Too Many Requests
Content-Length: 0
Ratelimit-Limit: 5
Ratelimit-Policy: 5;w=10
Ratelimit-Remaining: 0
Ratelimit-Reset: 10
Retry-After: 2
```

| Header                | O que diz                                     |
|-----------------------|-----------------------------------------------|
| `RateLimit-Limit`     | o `requests` da política                      |
| `RateLimit-Remaining` | quantas requisições ainda cabem agora         |
| `RateLimit-Reset`     | segundos até o orçamento encher de novo       |
| `RateLimit-Policy`    | `requests;w=<período em segundos>`            |
| `Retry-After`         | só no 429: segundos até caber mais uma        |

Os headers seguem o draft do IETF "RateLimit header fields for HTTP". Quando a rota e o teto global valem juntos, eles descrevem o orçamento mais apertado. O 429 vem sem corpo. O Traefik do lab muda a caixa dos nomes (`Ratelimit-Limit`), o que não faz diferença: nome de header HTTP não diferencia maiúsculas.

### Experimente

```bash
for i in 1 2 3 4 5 6; do curl -s -o /dev/null -w '%{http_code} ' "$GW/pol/limited/get"; done; echo
# 200 200 200 200 200 429

for key in a a a b; do curl -s -o /dev/null -w '%{http_code} ' -H "X-Api-Key: $key" "$GW/pol/per-client/get"; done; echo
# 200 200 429 200        a chave b tem orçamento próprio
```

### Cuidados

- **Réplicas:** o limite vale em cada pod (veja [Os limites contam por réplica](#os-limites-contam-por-réplica)).
- **`ip` atrás de proxies:** cada proxy confiável acrescenta ao `X-Forwarded-For` o endereço que viu, e o gateway lê o cliente na entrada que fica `gateway.traffic.trusted-proxies` posições antes do fim. A primeira entrada vem do cliente e pode ser forjada. O default é 1: só o nginx-lb acrescenta. Se outro proxy acrescenta antes dele, aumente o número; com `0`, vale o endereço TCP de quem conectou. A cadeia que chega aparece em `gateway.client.forwarded_for`, na [auditoria](#auditoria). No lab do k3d, o Traefik acrescenta antes do nginx-lb, e o header chega com duas entradas (`10.42.0.1, 10.42.0.134`). Com o default, o gateway conta pelo endereço do Traefik, e todo mundo divide o mesmo orçamento.
- **Mudar a política zera o orçamento da rota:** o orçamento novo nasce cheio.
- **Memória:** os orçamentos por cliente ficam limitados a `gateway.traffic.rate-limit.max-keys` (default 100 mil), e o orçamento que encheu de novo é descartado. Acima do teto, um cliente novo passa sem orçamento próprio, mas os orçamentos `route` e o teto global continuam valendo. Recusar todo mundo quando o mapa enche seria entregar um DoS a quem o enche.

---

## 2. Throttling

Limita quantas requisições da rota rodam ao mesmo tempo. O excesso espera numa fila, sem segurar thread, e é recusado com 503 quando a fila enche ou a espera vence. Serve para proteger um backend lento, ou com pool pequeno, de um pico de chamadas simultâneas.

O rate limit conta chegadas ao longo do tempo, e o throttling conta quem está lá dentro agora. Um backend que fica lento acumula conexões mesmo com poucas chegadas por segundo, e só o throttling segura isso.

```yaml
# v2 (GatewayRoute.spec)              # v1 (properties)
throttle:                             throttle:
  maxConcurrent: 50                     max-concurrent: 50
  maxQueue: 100                         max-queue: 100
  maxWait: 200ms                        max-wait: 200ms
```

| Campo           | Obrigatório | Default | Aceita                                            |
|-----------------|-------------|---------|---------------------------------------------------|
| `maxConcurrent` | sim         | —       | inteiro ≥ 1: requisições rodando ao mesmo tempo   |
| `maxQueue`      | não         | `0`     | inteiro ≥ 0: quantas esperam por uma vaga         |
| `maxWait`       | não         | `0`     | duração (`200ms`, `1s`): espera máxima na fila    |

### Como funciona

- A requisição ocupa a vaga do começo ao fim da resposta, corpo incluído. A vaga volta também quando a requisição dá erro e quando o cliente desiste.
- A vaga liberada vai para a requisição mais antiga da fila.
- Sem `maxQueue` ou sem `maxWait`, o excesso é recusado na hora.
- Com o teto global ligado, a requisição ocupa uma vaga global e uma da rota.

### O que o cliente recebe

Rota `policy-throttled` (`maxConcurrent: 1`, `maxQueue: 1`, `maxWait: 5s`), com três chamadas de 2 segundos (`/delay/2`) disparadas juntas:

```
chamada 1: 200 em 2,0 s     rodou
chamada 2: 200 em 3,8 s     esperou na fila a vaga da primeira
chamada 3: 503 em 0,001 s   fila cheia

HTTP/1.1 503 Service Unavailable
Content-Length: 0
Retry-After: 1
```

### Experimente

```bash
for i in 1 2 3; do curl -s -o /dev/null -w '%{http_code} em %{time_total}s\n' "$GW/pol/throttled/delay/2" & sleep 0.2; done; wait
```

### Escolhendo os números

- **`maxConcurrent`:** o que o backend aguenta em paralelo, dividido pelas réplicas do gateway (pool de conexões, threads).
- **`maxQueue`:** o tamanho dos picos curtos que vale absorver em vez de recusar.
- **`maxWait`:** menor que o timeout de quem chama. Esperar mais do que o cliente aguarda só gasta fila.

### Cuidados

- **Réplicas:** com 3 réplicas, até 3 × `maxConcurrent` chegam ao backend.
- **Respostas longas** (download, streaming, SSE) seguram a vaga o tempo todo.
- **Mudar o `throttle`** cria um limitador novo. As requisições em andamento terminam no antigo, e por alguns instantes as duas contas convivem.

---

## 3. Filtros

Mudam a requisição antes do backend e a resposta antes do cliente: headers, path, query e tamanho do corpo. Rodam na ordem declarada, depois do `stripPrefix`. São filtros do Spring Cloud Gateway, de um catálogo fechado, com os argumentos pelo nome. A declaração é igual no v1 e no v2.

```yaml
filters:
  - name: AddRequestHeader
    args:
      name: X-Source
      value: gateway
  - name: RemoveResponseHeader
    args:
      name: X-Powered-By
```

### Catálogo

| Filtro                   | Argumentos              | Exemplo de uso                                                  |
|--------------------------|-------------------------|-----------------------------------------------------------------|
| `AddRequestHeader`       | `name`, `value`         | mandar `X-Source: gateway` ao backend                           |
| `SetRequestHeader`       | `name`, `value`         | trocar o valor que o cliente mandou por um fixo                 |
| `RemoveRequestHeader`    | `name`                  | não repassar `X-Debug` ao backend                               |
| `AddResponseHeader`      | `name`, `value`         | devolver `Cache-Control: no-store`                              |
| `SetResponseHeader`      | `name`, `value`         | trocar o valor de um header da resposta                         |
| `RemoveResponseHeader`   | `name`                  | esconder `X-Powered-By` ou um header interno                    |
| `RewritePath`            | `regexp`, `replacement` | `/pedidos/(?<rest>.*)` vira `/v2/$\{rest}`                      |
| `PrefixPath`             | `prefix`                | `/x` vira `/api/x`                                              |
| `SetPath`                | `template`              | trocar o path inteiro, com as variáveis do path da rota         |
| `AddRequestParameter`    | `name`, `value`         | acrescentar `?origem=borda`                                     |
| `RemoveRequestParameter` | `name`                  | tirar `?debug=1` antes do backend                               |
| `RequestSize`            | `maxSize`               | `5MB`: corpo maior responde 413 (aceita `B`, `KB`, `MB` ou só bytes) |

### Receitas

**Prefixo novo no backend:** `/pedidos/42` chega ao backend como `/api/v2/orders/42`.

```yaml
path: /pedidos/**
stripPrefix: 1                    # /pedidos/42 → /42
filters:
  - name: PrefixPath
    args:
      prefix: /api/v2/orders      # /42 → /api/v2/orders/42
```

**Reescrita por expressão regular:** a `regexp` vê o path já sem o `stripPrefix`.

```yaml
path: /loja/**
stripPrefix: 1                    # /loja/itens/7 → /itens/7
filters:
  - name: RewritePath
    args:
      regexp: /(?<resto>.*)
      replacement: /catalogo/$\{resto}   # /itens/7 → /catalogo/itens/7
```

**Path fixo com variável da rota:** `/clientes/42/resumo` chega ao backend como `/api/customers/42/summary`.

```yaml
path: /clientes/{id}/resumo
filters:
  - name: SetPath
    args:
      template: /api/customers/{id}/summary
```

**Limite de corpo:** upload acima de 5 MB nem chega ao backend.

```yaml
filters:
  - name: RequestSize
    args:
      maxSize: 5MB
```

### O que o cliente recebe

A rota `policy-filters` declara `PrefixPath /anything`, `AddRequestHeader X-Source: gateway`, `RemoveRequestHeader X-Debug`, `AddRequestParameter origem=borda` e `AddResponseHeader X-Policy: filters`. Chamada com `X-Debug: 1`, o go-httpbin conta o que recebeu:

```json
{
  "url": "http://go-httpbin.platform.svc.cluster.local:8080/anything/headers?origem=borda",
  "args": {"origem": ["borda"]},
  "headers": {"X-Source": ["gateway"]}
}
```

O `X-Debug` não chegou ao backend, e a resposta voltou com `X-Policy: filters`.

A rota `policy-rewrite` declara `RewritePath /(?<resto>.*)` para `/anything/reescrito/$\{resto}`, `SetRequestHeader X-Canal: borda`, `RemoveRequestParameter debug` e `SetResponseHeader X-Policy: rewrite`. Chamada como `GET /pol/rewrite/pedidos?debug=1&pagina=2`, com `X-Canal: cliente`:

```json
{
  "url": "http://go-httpbin.platform.svc.cluster.local:8080/anything/reescrito/pedidos?pagina=2",
  "args": {"pagina": ["2"]},
  "headers": {"X-Canal": ["borda"]}
}
```

A rota `policy-setpath` declara `SetPath /response-headers`, `RemoveResponseHeader X-Interno` e `RequestSize 1KB`. O go-httpbin devolve como header cada parâmetro da query, e o gateway esconde o interno:

```
GET /pol/setpath/qualquer?X-Interno=segredo&X-Publico=ok
HTTP/1.1 200 OK
X-Publico: ok

POST /pol/setpath/qualquer, com 2048 bytes de corpo
HTTP/1.1 413 Request Entity Too Large
Content-Length: 0
Errormessage: Request size is larger than permissible limit. Request size is 2.0 kB where permissible limit is 1.0 kB
```

### Experimente

```bash
curl -s -H 'X-Debug: 1' "$GW/pol/filters/headers" | jq '{url, source: .headers["X-Source"], debug: .headers["X-Debug"]}'
curl -s -H 'X-Canal: cliente' "$GW/pol/rewrite/pedidos?debug=1&pagina=2" | jq '{url, canal: .headers["X-Canal"]}'
curl -s -D - -o /dev/null "$GW/pol/setpath/x?X-Interno=segredo&X-Publico=ok" | grep -i -E '^x-(interno|publico)'
head -c 2048 /dev/zero | tr '\0' a | curl -s -o /dev/null -w '%{http_code}\n' --data-binary @- "$GW/pol/setpath/x"   # 413
```

### Cuidados

- **Headers de identidade:** nenhum filtro de requisição pode criar, trocar ou remover `X-Auth-Request-*` (ou o prefixo configurado em `gateway.security.identity-headers.prefix`). É por esses headers que o backend sabe quem chama. Rota que tenta mexer neles é recusada.
- **`RewritePath` no v1:** escreva o grupo como `$\{resto}`. No `application.yaml`, `${resto}` é placeholder do Spring e pode ser trocado por uma propriedade ou variável de ambiente `RESTO`. O gateway lê `$\{resto}` como `${resto}` nos dois modos.
- **Argumentos pelo nome do filtro:** também no v1 é `maxSize`, não `max-size`.
- **Validação:** filtro fora do catálogo, argumento faltando ou sobrando, regex inválida, nome de header inválido e path que não começa com `/` deixam a rota fora.
- **Desempenho:** rota com `filters` sai do caminho rápido do `FastRouteCompiler` e compila pelo caminho normal do Spring Cloud Gateway. Rotas sem filtros não mudam. Num catálogo de dezenas de milhares de rotas, use filtros só onde precisa.
- **Filtro novo no catálogo** é mudança de código: o catálogo em `RoutePolicyRules`, o enum do CRD, o hint do binário nativo e este guia.

---

## 4. Transformação

Muda a resposta JSON do backend antes de ela chegar ao cliente: fica só com um pedaço, mantém ou tira campos, renomeia e embrulha. Serve para enxugar respostas grandes (app móvel), esconder campos sensíveis ou internos e adaptar nomes a um contrato, sem mexer no backend. A declaração é igual no v1 e no v2.

```yaml
transform:
  target: data.pedido        # fica só com o objeto em data.pedido
  deny: [senha]              # tira campos (ou allow: [...] para manter só esses)
  mapping:
    nome: cliente            # renomeia
  group: pedido              # embrulha em {"pedido": ...}
```

| Campo     | O que faz                                                                     |
|-----------|-------------------------------------------------------------------------------|
| `target`  | caminho com ponto até o objeto que interessa; se ele não existir, o resultado é `{}` |
| `allow`   | mantém só esses campos                                                        |
| `deny`    | tira esses campos (use `allow` ou `deny`, nunca os dois)                      |
| `mapping` | renomeia campos, no formato `nome-original: nome-novo`                        |
| `group`   | embrulha o resultado num campo                                                |

Todos os campos são opcionais. A ordem é a do KrakenD: `target`, depois `allow` ou `deny`, depois `mapping`, depois `group`. `allow`, `deny` e `mapping` olham os campos do primeiro nível, depois do `target`, pelo nome original. Numa lista, eles valem para cada objeto dela.

### Passo a passo

Resposta do backend:

```json
{
  "data": {
    "pedido": {"id": 42, "nome": "Ana", "senha": "s3cr3t", "itens": [{"sku": "A1", "qtd": 2}]}
  },
  "meta": {"versao": 3}
}
```

| Passo                      | Resultado                                                                       |
|----------------------------|---------------------------------------------------------------------------------|
| `target: data.pedido`      | `{"id": 42, "nome": "Ana", "senha": "s3cr3t", "itens": [...]}`                  |
| `deny: [senha]`            | `{"id": 42, "nome": "Ana", "itens": [...]}`                                     |
| `mapping: {nome: cliente}` | `{"id": 42, "cliente": "Ana", "itens": [...]}`                                  |
| `group: pedido`            | `{"pedido": {"id": 42, "cliente": "Ana", "itens": [{"sku": "A1", "qtd": 2}]}}`  |

Numa lista, `[{"id": 1, "nome": "A", "custo": 10}, {"id": 2, "nome": "B", "custo": 20}]` com `allow: [id, nome]` vira `[{"id": 1, "nome": "A"}, {"id": 2, "nome": "B"}]`.

### O que o cliente recebe

A rota `policy-transform` declara `allow: [method, url]`, `mapping: {url: endereco}` e `group: eco`. Sem a transformação, o go-httpbin devolve `args`, `data`, `files`, `form`, `headers`, `json`, `method`, `origin` e `url`. Com ela:

```
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8
Content-Length: 103

{"eco":{"method":"GET","endereco":"http://go-httpbin.platform.svc.cluster.local:8080/anything/pedido"}}
```

### Experimente

```bash
curl -s "$GW/pol/transform/pedido" | jq .
```

### Quando a resposta passa intacta

- O status não é 2xx: o erro do backend chega ao cliente como veio.
- O `Content-Type` não é JSON (`application/json` ou `*+json`).
- O corpo passa de `gateway.traffic.transform.max-body-size` (default 1 MB).

### Cuidados

- O gateway pede o corpo ao backend sem compressão (tira o `Accept-Encoding`), corrige o `Content-Length` e tira o `ETag`, que não vale para o corpo novo.
- A resposta é lida inteira antes de seguir, o que soma latência e memória por requisição, até o limite.
- `target` só anda por objetos: um caminho que passa por uma lista dá `{}`.

---

## 5. Agregação

Uma rota com `aggregate` chama vários backends em paralelo e devolve uma resposta JSON só, com as respostas juntas. Serve para a tela que precisa de dados de vários serviços: o front faz uma chamada em vez de várias. A rota declara `aggregate` no lugar de `targetUri`.

```yaml
# v2 (GatewayRoute.spec)
spec:
  path: /painel/{id}
  access:
    anyOf: [group:pedidos-leitura]
  aggregate:
    timeout: 2s
    backends:
      - url: http://pedidos.pedidos.svc.cluster.local:8080/pedidos/{id}
        group: pedido
        transform:
          allow: [id, status]
      - url: http://clientes.clientes.svc.cluster.local:8080/clientes/{id}
        group: cliente
```

```yaml
# v1 (properties): os mesmos campos
          - id: painel
            path: /painel/{id}
            access:
              any-of: [group:pedidos-leitura]
            aggregate:
              timeout: 2s
              backends:
                - url: http://pedidos.pedidos.svc.cluster.local:8080/pedidos/{id}
                  group: pedido
                  transform:
                    allow: [id, status]
                - url: http://clientes.clientes.svc.cluster.local:8080/clientes/{id}
                  group: cliente
```

| Campo                  | Obrigatório | O que é                                                                             |
|------------------------|-------------|-------------------------------------------------------------------------------------|
| `backends`             | sim         | de 2 a 10 backends                                                                  |
| `backends[].url`       | sim         | URL absoluta `http` ou `https`; `{nome}` vem do path da rota                        |
| `backends[].group`     | não         | chave sob a qual entra a resposta desse backend; única na rota                      |
| `backends[].transform` | não         | [transformação](#4-transformação) da resposta desse backend, antes da junção        |
| `timeout`              | não         | tempo máximo de cada chamada; o default é `gateway.traffic.aggregate.timeout` (5s)  |

A rota também pode ter `transform`, que vale para a resposta montada, além de `rateLimit`, `throttle` e `access`. Não pode ter `targetUri`, e `methods` só pode ser `[GET]` ou ficar ausente.

### Como a resposta é montada

- Com `group`, a resposta do backend entra sob essa chave.
- Sem `group`, os campos do backend entram direto no objeto final. Se dois backends trazem o mesmo campo, vale o do último da lista. Uma lista sem `group` entra em `collection`.
- O `transform` de cada backend roda antes da junção, e o da rota roda depois.

### O que vai para cada backend

- Sempre `GET`, com `Accept: application/json`.
- Os headers da requisição, do jeito que o `access` deixou: os de identidade (`X-Auth-Request-*`) e, em rota com `forwardToken`, o `Authorization`.
- Ficam de fora o `Cookie` (a sessão do cliente fica no gateway), o `Host`, o `Accept-Encoding` (as respostas são lidas como JSON puro), os headers por salto e os que descrevem um corpo.
- A query da requisição vai para todos os backends.
- As variáveis do path entram codificadas como segmento de URL.

### O que o cliente recebe

Rota `policy-painel`, `GET /pol/painel/42`:

```
HTTP/1.1 200 OK
Content-Type: application/json
X-Gateway-Completed: true

{
  "pedido": {"url": "http://go-httpbin.platform.svc.cluster.local:8080/anything/pedidos/42"},
  "cliente": {"method": "GET", "url": "http://go-httpbin.platform.svc.cluster.local:8080/anything/clientes/42"}
}
```

Rota `policy-parcial`, cujo segundo backend responde 503, `GET /pol/parcial/7`:

```
HTTP/1.1 200 OK
X-Gateway-Completed: false

{
  "pedido": {"url": "http://go-httpbin.platform.svc.cluster.local:8080/anything/pedidos/7"}
}
```

### Falhas

| Situação                                                                                           | Resposta                                                   |
|----------------------------------------------------------------------------------------------------|------------------------------------------------------------|
| um backend responde fora de 2xx, falha, estoura o `timeout`, devolve algo que não é JSON ou passa de `gateway.traffic.aggregate.max-body-size` | 200 sem ele, com `X-Gateway-Completed: false` |
| todos falham, e todos por estourar o tempo                                                          | 504, sem corpo                                             |
| todos falham, por qualquer outro motivo                                                             | 502, sem corpo                                             |
| método diferente de GET                                                                             | 404: a rota só casa com GET                                |

Resposta parcial também é 200, então quem consome a rota precisa olhar o `X-Gateway-Completed`. Backend que responde 2xx sem corpo entra como `{}`.

### Experimente

```bash
curl -s -i "$GW/pol/painel/42"
curl -s -i "$GW/pol/parcial/7"
curl -s "$GW/pol/painel/42?origem=app" | jq '.pedido.url, .cliente.url'   # a query vai para os dois backends
```

### Cuidados

- **Tempo:** cada chamada tem o `timeout` inteiro, e todas correm em paralelo. A rota responde em até `timeout`, mais o tempo de montar a resposta.
- **Cliente HTTP:** os backends são chamados pelo próprio gateway, com o mesmo cliente HTTP das rotas comuns (pool, timeouts e TLS).

---

## Contra uma API de verdade: JSONPlaceholder

As rotas `/jp/...` chamam a API pública do [JSONPlaceholder](https://jsonplaceholder.typicode.com), que serve posts, comentários e usuários de mentira em JSON. Cada rota mostra uma política. No v2, elas estão em [`k8s/samples/routes-jsonplaceholder.yaml`](k8s/samples/routes-jsonplaceholder.yaml): o `make lab` já as aplica, e `make jsonplaceholder-up` e `make jsonplaceholder-down` aplicam e tiram. No v1, as mesmas rotas vêm nos profiles `standalone` e `standalone-sso`. O gateway precisa de saída para a internet.

| Rota                     | O que mostra                                                                                                   |
|--------------------------|----------------------------------------------------------------------------------------------------------------|
| `/jp/posts/**`           | a API como ela é: `/jp/posts/1` é `/posts/1`                                                                   |
| `/jp/limitado/**`        | rate limit: 3 requisições a cada 10s para cada `X-Api-Key`                                                     |
| `/jp/lento/**`           | throttling: 1 por vez, 1 na fila                                                                               |
| `/jp/artigos/**`         | fachada em português: `RewritePath` para `/posts`, `mapping` dos campos, `X-Powered-By` da API escondido, `X-Fachada` acrescentado e corpo de até 1KB |
| `/jp/meus-posts`         | `SetPath /posts`, `AddRequestParameter userId=1` e `allow: [id, title]`                                        |
| `/jp/post-completo/{id}` | agregação: o post e os comentários numa resposta só, cada um reduzido                                          |
| `/jp/post-parcial/{id}`  | agregação com um backend que não existe: resposta parcial                                                      |

As rotas acima são públicas. Estas pedem login, com o SSO ligado, e usam os usuários do diretório do lab: alice (`tools-httpbin`), bob (sem grupo) e carol (`tools-admins` e `tools-httpbin`). A senha de todos sai no fim do `make lab`.

| Rota                       | Quem entra                                    | O que mostra                                                               |
|----------------------------|-----------------------------------------------|----------------------------------------------------------------------------|
| `/jp/privado/**`           | qualquer usuário logado (rota sem `access`)   | rate limit por usuário: 5 requisições a cada 10s para cada um (`key: user`) |
| `/jp/usuarios/**`          | grupo `tools-httpbin`                         | `transform` com `deny: [address, phone, company]`: os dados pessoais não saem |
| `/jp/gestao/**`, GET e HEAD | `tools-httpbin` ou `tools-admins`            | leitura                                                                    |
| `/jp/gestao/**`, POST, PUT, PATCH e DELETE | só `tools-admins`             | escrita: a mesma URL, outra regra por método                               |

Nenhuma rota privada usa `forwardToken`: o token do usuário não sai para uma API de terceiros. No v1, as privadas estão só no profile `standalone-sso`, porque sem SSO o gateway não confere o `access`.

Para testar no navegador, abra `http://localhost:8080/jp/usuarios/1` (a porta do seu `HOST_PORT`) e entre como alice: o usuário 1 chega sem `address`, `phone` e `company`. Entre como bob numa janela anônima: a mesma URL dá 403. Uma conta de serviço também passa, com token: o `tools-cli` (client credentials, segredo no Secret `sso-secrets`) lê `/jp/privado`, mas não tem grupo para `/jp/usuarios`.

### Subir só o gateway, com properties

As rotas `/jp/...` não dependem de nada do cluster, então dá para testar o gateway na própria máquina, com o profile `standalone`:

```bash
./gradlew :gateway:bootRun --args='--spring.profiles.active=standalone'   # JVM, em http://localhost:8080
# ou o binário nativo:
./gradlew :gateway:nativeCompile
gateway/build/native/nativeCompile/dynamic-gateway --spring.profiles.active=standalone

LOCAL=1 make jsonplaceholder-test        # confere cada política nesse gateway
```

As outras rotas do profile apontam para o go-httpbin de dentro do cluster e, fora dele, não respondem.

### O que o cliente recebe

```bash
GW=http://localhost:8080

curl -s -i $GW/jp/artigos/1
```

```
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8
X-Fachada: artigos

{"autorId":1,"id":1,"titulo":"sunt aut facere repellat provident occaecati excepturi optio reprehenderit","conteudo":"quia et suscipit\nsuscipit recusandae consequuntur expedita et cum\nreprehenderit molestiae ut ut quas totam\nnostrum rerum est autem sunt rem eveniet architecto"}
```

A API manda `userId`, `title`, `body` e `X-Powered-By: Express`; o cliente recebe `autorId`, `titulo`, `conteudo` e `X-Fachada`. O mesmo vale para a lista (`/jp/artigos`) e para a resposta do `POST /jp/artigos`, em que a API finge criar o post e devolve `id: 101`.

```bash
curl -s $GW/jp/meus-posts | jq -c '.[0:2]'
# [{"id":1,"title":"sunt aut facere repellat provident occaecati excepturi optio reprehenderit"},{"id":2,"title":"qui est esse"}]
# são os 10 posts do usuário 1, só com id e título

for i in 1 2 3 4; do curl -s -o /dev/null -w '%{http_code} ' -H 'X-Api-Key: minha-chave' $GW/jp/limitado/posts/1; done; echo
# 200 200 200 429
```

Com duas réplicas do gateway, passam até 6 chamadas (3 em cada uma), numa ordem que depende do balanceamento, por exemplo `200 200 200 200 429 200 200`: os limites contam por réplica.

```bash
curl -s -i $GW/jp/post-completo/1
```

```
HTTP/1.1 200 OK
X-Gateway-Completed: true

{
  "post": {"id": 1, "title": "sunt aut facere repellat provident occaecati excepturi optio reprehenderit", "body": "quia et suscipit\n..."},
  "comentarios": [
    {"name": "id labore ex et quam laborum", "email": "Eliseo@gardner.biz"},
    {"name": "quo vero reiciendis velit similique earum", "email": "Jayne_Kuhic@sydney.com"},
    ...
  ]
}
```

O post 1 tem 5 comentários; cada um chega só com `name` e `email`. `GET /jp/post-parcial/1` devolve só o post, com `X-Gateway-Completed: false`, porque o segundo backend responde 404.

No Postman, importe [`postman/dynamic-gateway-jsonplaceholder.postman_collection.json`](postman/dynamic-gateway-jsonplaceholder.postman_collection.json). São três pastas: as rotas públicas, as privadas com o token do `tools-cli` e as privadas com o cookie `GW_SESSION` copiado do navegador. Cada requisição tem testes, e a descrição da coleção diz o que preencher. O Runner roda tudo de uma vez, e o rate limit e o throttling disparam as chamadas extras por script.

`make jsonplaceholder-test` confere tudo isso. Com o SSO ligado, ele também faz login como alice, bob e carol, testa as rotas privadas e o token do `tools-cli` e confere a auditoria (`rate_limited`, `throttled`, `unauthenticated`, `forbidden`). No cluster, ele deixa as rotas lá para você testar na mão.

---

## Combinando políticas

Uma rota pode ter várias políticas. A rota abaixo exige login no grupo `pedidos-leitura`, dá 60 chamadas por minuto a cada usuário, deixa no máximo 20 chamadas simultâneas chegarem ao backend, avisa ao backend que a chamada veio da borda e esconde um campo interno da resposta:

```yaml
apiVersion: platform.saca.pags/v1alpha1
kind: GatewayRoute
metadata:
  name: pedidos
  namespace: platform
spec:
  path: /pedidos/**
  targetUri: http://pedidos.pedidos.svc.cluster.local:8080
  stripPrefix: 1
  team: time-pedidos
  access:
    anyOf: [group:pedidos-leitura]
  rateLimit:
    requests: 60
    period: 1m
    key: user
  throttle:
    maxConcurrent: 20
    maxQueue: 50
    maxWait: 300ms
  filters:
    - name: AddRequestHeader
      args:
        name: X-Origem
        value: borda
  transform:
    deny: [custoInterno]
```

Em cada requisição, nesta ordem:
1. o `access` confere o login e o grupo;
2. o rate limit gasta uma vaga do orçamento do usuário;
3. o throttle reserva uma vaga, ou põe a requisição na fila;
4. o filtro acrescenta o header;
5. o backend responde;
6. a transformação tira o `custoInterno`;
7. a vaga do throttle volta.

---

## Tetos globais

`gateway.traffic.*` vale para todas as rotas juntas, em cada réplica. Serve para proteger o próprio gateway, ou o cluster, de um volume que nenhuma rota sozinha barraria. O teto global vale junto com o da rota: a requisição precisa caber nos dois.

```yaml
gateway:
  traffic:
    trusted-proxies: 1            # proxies que acrescentam ao X-Forwarded-For (o nginx-lb)
    rate-limit:                   # soma das rotas, por réplica; desligado sem requests
      requests: 5000
      period: 1s                  # default 1s
      key: ip                     # opcional: teto por cliente em todas as rotas
      max-keys: 100000
    throttle:                     # desligado sem max-concurrent
      max-concurrent: 2000
      max-queue: 1000
      max-wait: 500ms
    transform:
      max-body-size: 1MB
    aggregate:
      timeout: 5s
      max-body-size: 1MB
```

- O rate limit global é conferido antes do da rota, então uma requisição que a rota recusa já gastou do global.
- Valor global inválido impede o gateway de subir, com a mensagem `gateway.traffic: <problema>`.
- Por variável de ambiente: `GATEWAY_TRAFFIC_RATE_LIMIT_REQUESTS=5000`, `GATEWAY_TRAFFIC_THROTTLE_MAX_CONCURRENT=2000`, `GATEWAY_TRAFFIC_TRUSTED_PROXIES=2`.

---

## Auditoria

As recusas entram no log de auditoria (logger `AUDIT`, consumido pelo SIEM), com `event.outcome: failure`:

| `event.reason` | Quando                                                                  |
|----------------|-------------------------------------------------------------------------|
| `rate_limited` | 429 do rate limit, da rota ou global                                    |
| `throttled`    | 503 do throttling, da rota ou global (e também o 429 do login LDAP)     |

Trecho de um evento real:

```json
{
  "event": {"action": "route.access", "outcome": "failure", "reason": "rate_limited"},
  "gateway": {
    "route": {"id": "policy-per-client", "team": "platform-team"},
    "access": {"mode": "public"},
    "client": {"forwarded_for": "10.42.0.1, 10.42.0.134"}
  },
  "http": {"request": {"method": "GET"}, "response": {"status_code": 429}},
  "url": {"path": "/pol/per-client/get"}
}
```

O contrato completo do evento está em [`GATEWAY_DE_BORDA.md`](GATEWAY_DE_BORDA.md).

---

## Problemas comuns

| Sintoma                                              | Causa                                                    | O que fazer                                                                                     |
|------------------------------------------------------|----------------------------------------------------------|-------------------------------------------------------------------------------------------------|
| `kubectl apply` recusa a rota                        | o schema do CRD pegou o erro                             | a mensagem diz o campo: `spec.rateLimit.requests: Invalid value: 0: ... should be greater than or equal to 1`, `spec.filters[0].name: Unsupported value: "Retry"`, `spec.transform: Invalid value: transform takes allow or deny, not both` |
| a rota foi aceita, mas responde 404                  | o operator a deixou fora do snapshot                     | log do operator: `GatewayRoute <nome> is invalid: <problema>`; o `listed N GatewayRoutes (... invalid)` de cada reconcile conta quantas |
| no v1, a rota responde 404                           | o gateway não a serve                                    | log do gateway: `Route <id> is invalid and will not be served: <problema>`                      |
| uma rota com `filters` responde 404, e as outras não | um filtro não compilou                                   | log do gateway: `Route <id> could not be compiled and will not be served: <problema>`           |
| o limite parece maior que o configurado              | cada réplica conta o seu                                 | divida pelo número de réplicas, ou fixe as réplicas                                             |
| todos os clientes dividem o mesmo orçamento `ip`     | `trusted-proxies` menor que o número de proxies na frente | confira `gateway.client.forwarded_for` na auditoria e ajuste `gateway.traffic.trusted-proxies` |
| a transformação não aconteceu                        | a resposta não é 2xx, não é JSON ou passa do limite      | confira o status e o `Content-Type` do backend, e `gateway.traffic.transform.max-body-size`     |
| a agregação responde 502                             | todos os backends falharam                               | teste cada URL de dentro do cluster: endereço, status e se a resposta é JSON                    |
| no v1, o `RewritePath` monta o path com outro valor  | `${resto}` foi resolvido como placeholder do Spring      | escreva `$\{resto}`                                                                             |

---

## Onde cada coisa roda

| Peça                 | Onde                                                                                         |
|----------------------|----------------------------------------------------------------------------------------------|
| Modelo e validação   | `shared/` (`RouteRateLimit`, ..., `RoutePolicyRules`), usado pelo operator e pelo gateway    |
| Schema               | `k8s/crd/gatewayroutes.platform.saca.pags.yaml`, com regras CEL                              |
| Filtros globais      | `gateway/.../traffic/`: `RateLimitFilter`, `ThrottleFilter`, `ResponseTransformFilter`, `AggregationFilter` |
| Exemplos e e2e       | `k8s/samples/routes-policies.yaml`, `application-standalone.yaml` e `scripts/e2e/test-policies.sh` (`make policies-test`) |

As políticas viajam como metadata da rota, como o `access`, e não como filtro por rota. Por isso as 60 mil rotas continuam no caminho rápido. Mudar só uma política dispara o reload, porque o hash do snapshot inclui as políticas, e um snapshot antigo, sem elas, continua carregando.
