# Gateway de borda: contrato e guia

Este repositório é o exemplo a partir do qual cada time constrói o seu gateway de borda. Cada gateway cuida da própria autenticação e do que fazer com quem não está logado. O que é comum entre todos é a forma de declarar quem pode usar cada rota.

| Reaproveita como está | Cada gateway implementa |
|---|---|
| CRD `GatewayRoute` (`k8s/crd/`) | O canal: como descobrir quem está chamando |
| Operator (`operator/`) | O que responder a quem chega sem identidade |
| Modelo e formato do snapshot (`shared/`) | De onde vêm as permissões do usuário |
| A tabela de decisão e os headers abaixo | |

Um gateway em outra stack pode ler o snapshot direto do store, desde que siga este contrato.

## Contrato da rota

```yaml
spec:
  path: /pedidos/**
  targetUri: http://pedidos-bff.pedidos.svc.cluster.local:8080
  methods: [GET]
  access:
    mode: authenticated
    anyOf:
      - group:tools-pedidos-leitura
      - scope:pedidos.read
      - permission:pedidos-consulta
```

- **`mode`**: `public` não exige identidade; `authenticated` exige.
- **`anyOf`**: quem chama precisa de pelo menos uma destas permissões, no formato `<tipo>:<nome>`.
- **`<tipo>`**: letras minúsculas, dígitos e hífen, começando por letra. É livre, e cada gateway define os seus.
- **`forwardToken`**: repassa o access token ao backend. Só vale em canal com token. Em canal sem token, é ignorado.

Permissão por método usa duas rotas com o mesmo `path` e `methods` diferentes, como `/sso/rw/**` em `k8s/samples/routes-sso.yaml`.

Cada gateway tem seu namespace, seu operator e seu snapshot. Uma rota exposta em dois gateways vira dois recursos `GatewayRoute`. O exemplo está em `k8s/second-gateway/`.

### Tabela de decisão

Primeiro resolva o modo efetivo:

| `access` da rota | Modo efetivo |
|---|---|
| Ausente | `gateway.security.default-access` |
| Sem `mode`, com `anyOf` | `authenticated` |
| Sem `mode`, sem `anyOf` | `gateway.security.default-access` |
| `mode` desconhecido | `authenticated` |
| `public` com `anyOf` | `authenticated` |

Depois aplique a decisão:

| Modo efetivo | `anyOf` | Identidade | Resultado |
|---|---|---|---|
| `public` | | Qualquer | Encaminha |
| `authenticated` | Vazio | Presente | Encaminha |
| `authenticated` | Com itens | Tem uma das permissões | Encaminha e audita `matched` |
| `authenticated` | Com itens | Não tem nenhuma | 403 |
| `authenticated` | Qualquer | Ausente | Resposta do canal |
| `authenticated` | Qualquer | Provedor fora do ar | 503, sem redirecionar |

Permissão de um tipo que o gateway não produz não autoriza ninguém, e a rota responde 403. Por isso nenhum gateway pode tratar `anyOf` como opcional.

### Snapshot

O operator consolida as rotas do namespace e publica um snapshot ordenado por `id`. O campo `version` é o instante da geração, não uma versão de schema.

```yaml
version: "2026-10-30T17:03:12.345Z"
generatedAt: "2026-10-30T17:03:12.345Z"
routes:
  - id: pedidos
    path: /pedidos/**
    targetUri: http://pedidos-bff.pedidos.svc.cluster.local:8080
    stripPrefix: 1
    methods: [GET]
    enabled: true
    team: pedidos
    description: Consulta de pedidos
    access:
      mode: authenticated
      anyOf: [group:tools-pedidos-leitura]
      forwardToken: false
```

| Store | Onde fica | Como o gateway percebe a mudança |
|---|---|---|
| ConfigMap | `binaryData[routes.yaml.gz]` (YAML com gzip) | Watch da ConfigMap |
| S3 | Objeto YAML | Polling de ETag |

O reload precisa ser idempotente: um sinal repetido, ou fora de ordem, não pode mudar nada. O hash do snapshot tem que incluir `access`, senão uma troca só de permissão não dispara o reload.

### Headers para o backend

Antes de encaminhar, o gateway remove qualquer header de entrada com o prefixo `X-Auth-Request-` e injeta os verificados:

| Header | Conteúdo |
|---|---|
| `X-Auth-Request-User` | Nome do usuário |
| `X-Auth-Request-Email` | E-mail |
| `X-Auth-Request-Subject` | Identificador estável |
| `X-Auth-Request-Authorities` | Todas as permissões, como `group:a,scope:b`. No valor, `,` vira `%2C` e `%` vira `%25` |
| `X-Auth-Request-Groups` | Só os grupos, sem o tipo |
| `X-Auth-Request-Roles` | Só as roles, sem o tipo |
| `X-Auth-Request-Client-Id` | Client, em chamada com token |
| `X-Auth-Request-<nome>-Truncated` | `true` quando a lista passou de `max-length` |

Gateway novo deve usar `X-Auth-Request-Authorities`. `Groups` e `Roles` continuam existindo para os backends atuais. Cada header aceita até `gateway.security.identity-headers.max-length`, então confira o limite de headers do backend. O Tomcat, por exemplo, aceita 8 KiB no total por padrão.

Os cookies do gateway nunca são repassados.

### Endpoints reservados

O prefixo `/_gateway/` é do gateway, e o operator rejeita rotas sob ele.

| Endpoint | Função |
|---|---|
| `GET /_gateway/whoami` | Identidade atual, ou 401 |
| `GET /_gateway/login/start?returnTo=<url>` | Inicia o login e volta para `returnTo`. 404 em canal sem login |
| `GET` ou `POST /_gateway/logout` | Encerra a sessão. 404 em canal sem sessão |

### Auditoria

Cada decisão gera uma linha JSON no logger `AUDIT`. Esse formato é consumido por SIEM e SOC: todo gateway deve emitir a mesma forma, e mudar um campo é mudança de contrato.

| Campo | Conteúdo |
|---|---|
| `event.action` | `route.access`, `auth.login`, `auth.logout` e outros |
| `event.outcome` e `event.reason` | `success` ou `failure`, e o motivo (`allowed`, `forbidden`, `unauthenticated`, `invalid_token`, `session_expired`) |
| `user.id`, `user.name`, `user.email` | Quem chamou |
| `gateway.auth.method` | `session`, `bearer` ou `none` |
| `gateway.route.id` e `gateway.route.team` | A rota |
| `gateway.access.required` | O `anyOf` da rota, com o tipo |
| `gateway.access.matched` | A permissão que liberou o acesso |

Nunca registre token, cookie, `Authorization`, senha ou query string. Preflight de CORS respondido pelo gateway não gera evento, porque não chega a uma rota. Já uma chamada de origem recusada gera evento com `event.reason=forbidden`.

## Modelos de integração de front-end

Todo tráfego externo passa pela borda, que valida a sessão e encaminha. O que muda entre os modelos é quem entrega o HTML e quem agrega os dados. `make frontend-test` prova os modelos abaixo que têm comportamento no gateway.

| Modelo | Situação | O que o gateway faz |
|---|---|---|
| 1. Estático + BFF | Válido | Sem sessão, o AJAX recebe 401 e o front chama `/_gateway/login/start`. Com o front em outra origem, configure `gateway.security.front-origins` |
| 2. SSR puro | Válido | Navegação sem sessão vai para o login e volta para a página |
| 3. SSR ou híbrido, AJAX na própria app | Válido | Mesma sessão nas páginas e nos route handlers. AJAX sem sessão recebe 401, nunca redirect |
| 4. Híbrido + BFF separado | Válido | Mesma sessão em duas rotas com `targetUri` diferentes |
| 5. SSR chama BFF para montar o HTML | Inválido | Fora da borda. Regra de arquitetura |
| 6. Navegador fora da borda | Inválido | Bloqueie com `NetworkPolicy`, como `k8s/samples/network-policy-backend.yaml`. O backend confia nos headers do gateway, então não pode aceitar outra origem de tráfego |
| 7. Estático sem BFF | Inválido | O gateway não distingue BFF de API de domínio. Revise o `targetUri` das rotas |
| 8. SSR agrega + BFF para o AJAX | Inválido | Regra de arquitetura |
| 9. App SSR servindo outros consumidores | Inválido | Declare grupos de usuário no `anyOf` das rotas de front: token de outro sistema não tem esses grupos e recebe 403 |

### Front estático em outra origem

```yaml
gateway:
  security:
    front-origins:
      - https://app.exemplo.com
```

Essa lista libera duas coisas: CORS com credenciais e o retorno depois do login. Cada item é uma origem exata, sem curinga: um valor como `https://*.exemplo.com` impede o gateway de subir. A URL pública do gateway entra sozinha. Um `returnTo` fora da lista recebe 400, e URL com `|` ou `{` sem codificação também. Se a lista ficar vazia, o gateway não responde CORS, o que basta quando o front e o gateway estão na mesma origem.

```js
const response = await fetch('https://gateway.exemplo.com/pedidos', { credentials: 'include' });
if (response.status === 401) {
  const returnTo = encodeURIComponent(location.href);
  location.href = `https://gateway.exemplo.com/_gateway/login/start?returnTo=${returnTo}`;
}
```

Quando o gateway responde CORS, ele descarta os cabeçalhos `Access-Control-*` do backend, porque o navegador recusa `Access-Control-Allow-Origin` duplicado. A exceção é `Access-Control-Expose-Headers`: só o backend sabe quais headers o front precisa ler. Quem implementar outro gateway precisa do mesmo cuidado.

O cookie de sessão é `SameSite=Lax`, então o front precisa estar no mesmo site do gateway (`app.exemplo.com` e `gateway.exemplo.com`). Em domínios diferentes, o navegador não envia o cookie no AJAX.

### O que fica fora do gateway

- **WAF**: os modelos colocam WAF na borda, e este repositório não tem WAF. Ele precisa ficar antes do gateway. Em qual camada é uma decisão de cada ambiente.
- **CSRF**: o gateway não valida token CSRF nas rotas. A proteção vem do `SameSite=Lax`, que barra `POST` entre sites, e da regra de que `GET` não muda estado. Uma app em subdomínio não confiável é do mesmo site: nesse caso, a app precisa validar um token próprio.

## Como criar um canal

Um canal responde a três perguntas: quem é o usuário, o que fazer com quem chega sem identidade, e quais cookies são dele. O exemplo mais curto é `OAuthSecurity`, com menos de 60 linhas.

1. Crie a classe em `security/<canal>/`, implementando `IdentityChannel`, com um `create` que falha quando falta configuração.
2. Em `configure(http)`, parta de `SecurityChains.common` e acrescente as credenciais do canal.
3. Em `unauthenticatedHandler()`, devolva a resposta do canal. `BearerChallengeHandler` responde 401 e `BrowserLoginHandler` manda a navegação para o login.
4. Monte a `GatewayIdentity` com as permissões em `<tipo>:<nome>`, os mesmos valores que as rotas declaram.
5. Registre a fábrica em `ActiveSecurity.CHANNELS` e crie a constante em `GatewaySecurityProperties`.
6. Crie `application-<canal>.yaml`, sem segredo, e `k8s/sso/gateway-<canal>.yaml`.
7. Cubra com um teste de integração, como `OAuthModeIntegrationTest`, e inclua o profile no `GatewayProfilesTest`.

Não use `@ConditionalOnProperty` para escolher o canal: o AOT avalia a condição no build do binário nativo. A escolha acontece no startup, em `ActiveSecurity`.

### Canal com sessão própria

Para um gateway que já tem sessão, como o SACA ou o MIB:

1. Implemente `GatewaySessionManager`. O `resolve` lê a sessão do canal e devolve a `GatewayIdentity`, e o `logout` encerra a sessão e diz para onde ir.
2. No `configure`, use `.securityContextRepository(new SessionSecurityContextRepository(sessionManager))`.
3. Em `gatewayCookieNames()`, liste os cookies que só o gateway lê.

Se o provedor da sessão estiver fora do ar, lance `ProviderUnavailableException`: a rota responde 503 em vez de mandar o usuário para um login que também vai falhar.

## Regras que todo gateway precisa seguir

1. **Negue na dúvida**: modo desconhecido vira `authenticated`, e permissão desconhecida não autoriza.
2. **Descarte headers de identidade vindos de fora**, em toda rota, inclusive nas públicas.
3. **Nunca repasse os cookies do gateway** ao backend.
4. **Valide o `returnTo` na ida e na volta**, para o login não virar open redirect.
5. **Audite cada decisão**, sem dados sensíveis.
6. **Mantenha o backend alcançável só pelo gateway.**
7. **Valide `iss`, `aud` e `exp`** em todo token. Use como `aud` o identificador da API, nunca um client id: o ID token traz o client id no `aud`.
8. **Bloqueie `POST /internal/routes/reload` na borda.** Ele não tem autenticação, porque o operator chama de dentro do cluster. Aqui quem bloqueia é o nginx-lb. Sem ele, exponha só os prefixos das rotas, como em `k8s/second-gateway/ingress.yaml`.
