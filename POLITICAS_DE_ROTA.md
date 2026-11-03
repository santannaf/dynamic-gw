# Políticas de rota: rate limit, throttling, filtros, transformação e agregação

Cada rota pode declarar, além do `access`, cinco políticas: `rateLimit`, `throttle`, `filters`, `transform` e `aggregate`. Elas ficam na própria rota, com os mesmos nomes no `GatewayRoute` (v2) e nas rotas do properties (v1, em kebab-case). Os tetos que valem para todas as rotas juntas ficam em `gateway.traffic.*`, no `application.yaml` do gateway. O trabalho está na história ETT-988.

| Política    | O que faz                                                          | Resposta quando barra       |
|-------------|--------------------------------------------------------------------|-----------------------------|
| `rateLimit` | conta requisições por período, para a rota toda ou por cliente     | 429, com `Retry-After`      |
| `throttle`  | limita requisições simultâneas, com fila e espera máxima           | 503, com `Retry-After`      |
| `filters`   | mexe em cabeçalhos, path, query e tamanho, com filtros do catálogo | 413 (`RequestSize`)         |
| `transform` | reduz, renomeia e agrupa campos da resposta JSON                   | —                           |
| `aggregate` | chama vários backends em paralelo e devolve uma resposta só        | 502 ou 504, se todos falham |

As rotas de exemplo estão em [`k8s/samples/routes-policies.yaml`](k8s/samples/routes-policies.yaml) (v2) e no fim do [`application-standalone.yaml`](gateway/src/main/resources/application-standalone.yaml) (v1). `make policies-test` roda o e2e nos dois modos.

---

## Antes de tudo: os limites contam por réplica

Rate limit e throttling ficam em memória, em cada pod do gateway, como no KrakenD Community. Não há Redis. Com 3 réplicas atrás do nginx-lb, uma rota com `requests: 100` aceita até 300 requisições por período no total. Divida o teto desejado pelo número de réplicas, ou fixe as réplicas.

---

## `rateLimit`: requisições por período

```yaml
# v2 (GatewayRoute.spec)              # v1 (gateway.routes.store.properties.routes[*])
rateLimit:                            rate-limit:
  requests: 100                         requests: 100
  period: 1s                            period: 1s
  burst: 20                             burst: 20
  key: ip                               key: ip
```

| Campo      | Obrigatório | O que é                                                                                              |
|------------|-------------|------------------------------------------------------------------------------------------------------|
| `requests` | sim         | requisições aceitas por período                                                                      |
| `period`   | sim         | `500ms`, `1s`, `1m` ou `1h`                                                                          |
| `burst`    | não         | quantas podem chegar de uma vez; o default é `requests`                                              |
| `key`      | não         | de quem é o orçamento: `route` (default, todos juntos), `ip`, `user`, `client-id` ou `header:<nome>` |

- **Algoritmo:** GCRA, uma variante de token bucket com um único timestamp por chave, atualizado sem lock. As requisições ficam espaçadas `period / requests` umas das outras, com até `burst` de uma vez.
- **`ip`:** usa a entrada do `X-Forwarded-For` acrescentada pelo proxy confiável (`gateway.traffic.trusted-proxies`, default 1, o nginx-lb), e não a primeira. A primeira vem do cliente e pode ser forjada.
- **`user` e `client-id`:** vêm da identidade, por isso o rate limit roda depois do `access`. Quem chega sem identidade cai no orçamento do seu IP. O mesmo vale para `header:<nome>` sem o header. Do valor do header, só o hash fica em memória: ele costuma ser uma API key.
- **Respostas:** toda resposta de rota limitada traz `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset` e `RateLimit-Policy`, do draft do IETF. A recusa é 429 com `Retry-After`, e a auditoria registra `reason: rate_limited`.
- **Memória:** orçamentos por cliente ficam limitados a `gateway.traffic.rate-limit.max-keys` (default 100 mil). Um orçamento cheio de novo é descartado. Acima do teto, cliente novo não ganha orçamento próprio, mas o da rota e o global continuam valendo. Recusar todo mundo quando o mapa enche seria entregar um DoS a quem o enche.

---

## `throttle`: requisições simultâneas

```yaml
throttle:                             throttle:
  maxConcurrent: 50                     max-concurrent: 50
  maxQueue: 100                         max-queue: 100
  maxWait: 200ms                        max-wait: 200ms
```

- **Funcionamento:** até `maxConcurrent` requisições rodam ao mesmo tempo. As seguintes esperam na fila, sem segurar thread, por até `maxWait`, e a vaga liberada vai para a mais antiga.
- **Recusa:** fila cheia ou espera vencida respondem 503 com `Retry-After: 1`, e a auditoria registra `reason: throttled`.
- **Devolução da vaga:** a vaga volta quando a resposta termina, inclusive em erro e quando o cliente desiste.
- **Defaults:** sem `maxQueue` ou sem `maxWait`, o excesso é recusado na hora.

Rate limit protege contra volume ao longo do tempo. Throttling protege o backend de picos de concorrência, como um backend lento acumulando conexões.

---

## `filters`: filtros do catálogo

Os filtros rodam na ordem declarada, depois do `stripPrefix`. São filtros do Spring Cloud Gateway, com os argumentos pelo nome:

| Filtro                   | Argumentos              | Exemplo                                                                 |
|--------------------------|-------------------------|-------------------------------------------------------------------------|
| `AddRequestHeader`       | `name`, `value`         | manda `X-Source: gateway` ao backend                                    |
| `SetRequestHeader`       | `name`, `value`         | troca o valor de um header da requisição                                |
| `RemoveRequestHeader`    | `name`                  | não repassa `X-Debug`                                                   |
| `AddResponseHeader`      | `name`, `value`         | devolve `Cache-Control: no-store`                                       |
| `SetResponseHeader`      | `name`, `value`         | troca um header da resposta                                             |
| `RemoveResponseHeader`   | `name`                  | esconde `X-Powered-By`                                                  |
| `RewritePath`            | `regexp`, `replacement` | `/pedidos/(?<rest>.*)` vira `/v2/$\{rest}`                              |
| `PrefixPath`             | `prefix`                | `/x` vira `/api/x`                                                      |
| `SetPath`                | `template`              | troca o path inteiro (aceita as variáveis do path da rota)              |
| `AddRequestParameter`    | `name`, `value`         | acrescenta `?origem=borda`                                              |
| `RemoveRequestParameter` | `name`                  | tira um parâmetro da query                                              |
| `RequestSize`            | `maxSize`               | `5MB`: acima disso, 413                                                 |

```yaml
filters:
  - name: RewritePath
    args:
      regexp: /pedidos/(?<rest>.*)
      replacement: /v2/$\{rest}       # $\{...}: no v1, ${...} seria placeholder do Spring
  - name: AddRequestHeader
    args:
      name: X-Source
      value: gateway
```

- **Argumentos pelo nome do filtro, nos dois modos:** `args` é um mapa repassado ao filtro, então no v1 também é `maxSize`, não `max-size`.
- **`RewritePath` no v1:** escreva o grupo como `$\{rest}`. No `application.yaml`, `${rest}` é placeholder do Spring e pode ser trocado por uma variável de ambiente `REST`. O gateway lê `$\{rest}` como `${rest}` nos dois modos.
- **Validação:** filtro fora do catálogo, argumento faltando ou sobrando, regex inválida e header inválido deixam a rota fora. O operator a tira do snapshot com warn; no v1, o gateway não a serve.
- **Headers de identidade:** nenhum filtro de requisição pode criar, trocar ou remover `X-Auth-Request-*` (ou o prefixo configurado em `gateway.security.identity-headers.prefix`). Esses headers são o que o backend usa para confiar em quem chama.
- **Desempenho:** rota com `filters` sai do caminho rápido do `FastRouteCompiler` e compila pelo caminho normal do Spring Cloud Gateway. Rotas sem filtros não mudam.

---

## `transform`: resposta JSON

```yaml
transform:
  target: data.pedido        # fica só com o objeto dentro de data.pedido
  allow: [id, nome, itens]   # ou deny: [senha]
  mapping:
    nome: name               # renomeia
  group: pedido              # embrulha o resultado em {"pedido": ...}
```

- **Ordem:** a mesma do KrakenD: `target`, depois `allow` ou `deny`, depois `mapping`, depois `group`.
- **Campos:** `allow` e `deny` olham os campos do primeiro nível, com os nomes originais. Numa lista, valem para cada objeto dela. `target` aceita caminho com ponto; se não existir, o resultado é `{}`.
- **O que é transformado:** só respostas 2xx com `Content-Type` JSON. O gateway pede o corpo ao backend sem compressão e corrige o `Content-Length`.
- **Tamanho:** o corpo é lido até `gateway.traffic.transform.max-body-size` (default 1 MB). Acima disso, e também em resposta que não é JSON, a resposta passa intacta.

---

## `aggregate`: várias chamadas, uma resposta

```yaml
spec:
  path: /painel/{id}
  aggregate:                     # no lugar de targetUri
    timeout: 2s
    backends:
      - url: http://pedidos.svc:8080/pedidos/{id}
        group: pedido
        transform:
          allow: [id, status]
      - url: http://clientes.svc:8080/clientes/{id}
        group: cliente
```

Resposta de `GET /painel/42`:

```json
{"pedido": {"id": 42, "status": "pago"}, "cliente": {"id": 42, "nome": "Ana"}}
```

- **Requisição:** só GET. As chamadas saem em paralelo com os headers da requisição, já do jeito que o `access` deixou (identidade, e `Authorization` quando a rota usa `forwardToken`). A query vai junto. Ficam de fora `Cookie`, `Host`, os headers por salto e `Accept-Encoding`.
- **Variáveis do path:** `{id}` na URL do backend vem do path da rota.
- **Montagem:** com `group`, a resposta do backend fica sob essa chave. Sem `group`, os campos se juntam no objeto final, e uma lista vai para `collection`. O `transform` de cada backend roda antes da junção; o da rota, depois.
- **Falhas:** backend que falha (não 2xx, erro ou corpo maior que `gateway.traffic.aggregate.max-body-size`) ou que estoura o `timeout` fica de fora, e a resposta leva `X-Gateway-Completed: false`. Se todos falham, a resposta é 504 quando todos estouraram o tempo, e 502 nos outros casos.
- **Limites da rota:** `timeout` ausente usa `gateway.traffic.aggregate.timeout` (default 5s). A rota declara `targetUri` ou `aggregate`, nunca os dois, e tem de 2 a 10 backends.

---

## Tetos globais (`gateway.traffic.*`)

```yaml
gateway:
  traffic:
    trusted-proxies: 1            # proxies que acrescentam ao X-Forwarded-For (o nginx-lb)
    rate-limit:                   # vale para a soma das rotas, por réplica; desligado sem requests
      requests: 5000
      period: 1s
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

O teto global vale junto com o da rota: a requisição precisa caber nos dois. Valor global inválido impede o gateway de subir. Por variável de ambiente: `GATEWAY_TRAFFIC_RATE_LIMIT_REQUESTS=5000`, `GATEWAY_TRAFFIC_THROTTLE_MAX_CONCURRENT=2000`.

---

## Onde cada coisa roda

| Peça                      | Onde                                                          |
|---------------------------|---------------------------------------------------------------|
| Modelo e validação        | `shared/` (`RouteRateLimit`, ..., `RoutePolicyRules`), usado pelo operator e pelo gateway |
| Schema                    | `k8s/crd/gatewayroutes.platform.saca.pags.yaml`, com regras CEL |
| Filtros globais           | `gateway/.../traffic/`: `RateLimitFilter`, `ThrottleFilter`, `ResponseTransformFilter`, `AggregationFilter` |
| Ordem por requisição      | access → rate limit → throttle → filtros da rota → agregação ou proxy → transformação da resposta |

As políticas viajam como metadata da rota, como o `access`, e não como filtro por rota. Por isso as 60 mil rotas continuam no caminho rápido. Mudar só uma política dispara o reload (o hash do snapshot inclui as políticas), e um snapshot antigo, sem elas, continua carregando.
