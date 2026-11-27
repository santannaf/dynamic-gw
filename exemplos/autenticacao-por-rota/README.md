# Autenticação por rota: exemplos por cenário

Exemplos de [AUTENTICACAO_POR_ROTA.pdf](../../AUTENTICACAO_POR_ROTA.pdf), implementado na ETT-1452. Cada pasta é um cenário de gateway em um store, com a configuração do gateway (`application.yaml`), as rotas (`rotas.yaml`: uma por estratégia que o cenário comporta e o [mostruário das políticas](#mostruário-das-políticas)) e um desenho por estratégia. Nada aqui é carregado pelo gateway, pelo operator ou pelos scripts: as rotas que o lab usa estão na seção [No lab](#no-lab).

| Cenário | O gateway | Estratégias | Profiles do gateway (properties · crd · s3) | Campos da ETT-1452 |
|---|---|---|---|---|
| 0 | Não autentica nem autoriza | SEM AUTENTICAÇÃO | `standalone` · — · — | Nenhum |
| 1 | Faz o LDAP direto | PUBLIC, LDAP | `standalone-sso,ldap` · `ldap` · `s3,ldap` | Nenhum |
| 2 | Usa o Keycloak com LDAP | PUBLIC, LDAP | `standalone-sso,keycloak` · `keycloak` · `s3,keycloak` | `audience` |
| 3 | Usa o Keycloak com Identity Provider | PUBLIC, IDENTITY_PROVIDER, OBO | `standalone-sso,keycloak` · `keycloak` · `s3,keycloak` | `identity-provider`, `audience`, `obo` |
| 4 | Usa o Keycloak com Identity Provider e LDAP | PUBLIC, LDAP, IDENTITY_PROVIDER, OBO | `standalone-sso,keycloak` · `keycloak` · `s3,keycloak` | `identity-provider`, `audience`, `obo` |
| 5 | Faz o LDAP direto e usa o Keycloak com Identity Provider | PUBLIC, LDAP direto, IDENTITY_PROVIDER, OBO | `standalone-sso,keycloak-ldap` · `keycloak-ldap` · `s3,keycloak-ldap` | `additional-providers`, `channel` e os do cenário 3 |

O cenário 0 só existe em `properties/`: é o gateway v1 sem SSO (`gateway.security.enabled: false`), o ponto de partida dos outros. Toda rota fica aberta, e continuam valendo as políticas de rota e a auditoria. O profile `standalone` deixa a auditoria desligada, e o `application.yaml` do cenário a liga. Mesmo sem login, o gateway tira os `X-Auth-Request-*` e os cookies dele que vieram do cliente, e `GET /internal/routes` só responde a quem chama o pod direto (`make routes`), não pela borda.

O cenário 4 é o realm do lab. O 5 é o único com dois canais no mesmo gateway: duas sessões (`GW_SESSION` e `GW_SESSION_LDAP`), dois logins e a configuração dos dois provedores juntas.

## Desenhos

Cada pasta de cenário tem, ao lado dos YAML, um PNG por estratégia: `desenho-<n>-<estratégia>.png`. O desenho mostra o sistema do cenário, e o que a estratégia não usa fica em cinza claro. O caminho da requisição vem numerado, passando pelas etapas do gateway (acesso, rate limit, throttling, token do backend e proxy). Embaixo aparece de onde vêm as rotas naquele store: o `rotas.yaml` na imagem (properties) ou o operator gravando o snapshot no ConfigMap (crd) ou no S3 (s3). Ao lado ficam o passo a passo, a rota como está no `rotas.yaml` da pasta, o que o backend recebe e as recusas. Exemplo: [`crd/cenario-4-keycloak-idp-e-ldap/desenho-4-obo.png`](crd/cenario-4-keycloak-idp-e-ldap/desenho-4-obo.png).

| Cenário | Desenhos na pasta do cenário, em cada store |
|---|---|
| 0 | `desenho-1-sem-autenticacao.png` (só em `properties/`) |
| 1 | `desenho-1-public.png`, `desenho-2-ldap-direto.png` |
| 2 | `desenho-1-public.png`, `desenho-2-ldap-keycloak.png` |
| 3 | `desenho-1-public.png`, `desenho-2-identity-provider.png`, `desenho-3-obo.png` |
| 4 | `desenho-1-public.png`, `desenho-2-ldap-keycloak.png`, `desenho-3-identity-provider.png`, `desenho-4-obo.png` |
| 5 | `desenho-1-public.png`, `desenho-2-ldap-direto.png`, `desenho-3-identity-provider.png`, `desenho-4-obo.png` |

Tracejado é o login, uma vez por sessão; contínuo, o que acontece a cada requisição; pontilhado, a auditoria. Laranja é Keycloak, azul é Entra ID, verde é AD e cinza, as rotas que vêm do store.

## Pastas

Uma pasta por store e, dentro dela, uma por cenário (`properties/cenario-3-keycloak-idp/`, por exemplo), com:

- `application.yaml`: a configuração do gateway no cenário, num arquivo só, com a explicação do cenário (realm, login, segredos). É o que os profiles da tabela acima montam neste gateway, com os valores do lab.
- `rotas.yaml`: as rotas do cenário, no formato do store.
- `desenho-<n>-<estratégia>.png`: um desenho por estratégia ([Desenhos](#desenhos)).

| Store | `application.yaml` | `rotas.yaml` |
|---|---|---|
| `properties/` (v1) | `type: properties`; importa o `rotas.yaml` ao lado (`spring.config.import`) | As rotas no YAML do gateway, em `gateway.routes.store.properties` |
| `crd/` (v2, snapshot no ConfigMap, o default) | `type: configmap` | Os `GatewayRoute` que o time escreve |
| `s3/` (v2, snapshot no S3) | `type: s3` | O objeto que o operator grava em `s3://<bucket>/snapshots/routes.yaml`, no formato do `SnapshotCodec`, a partir dos `GatewayRoute` de `crd/` |

`crd/schema-access.yaml` é o schema do `access`, o mesmo do CRD em `k8s/crd/`.

Do cenário 1 ao 5, os três `rotas.yaml` de um cenário têm as mesmas rotas, com os mesmos ids, o mesmo `access` e as mesmas políticas, e os três `application.yaml` só mudam em `gateway.routes.store`. A exceção é a rota desligada do mostruário, que só existe em `crd/`. As rotas das estratégias também trazem rate limit (`ip` na pública e em todas as do cenário 0, `user` nas autenticadas) e throttling ([POLITICAS_DE_ROTA.md](../../POLITICAS_DE_ROTA.md)).

## Mostruário das políticas

Depois das rotas de cada estratégia, todo `rotas.yaml` traz as mesmas rotas de mostruário: backends de mentira, que não existem no lab, com as políticas do [POLITICAS_DE_ROTA.md](../../POLITICAS_DE_ROTA.md). Entre os cenários as políticas não mudam. Muda o `access` e, com ele, a `key` do rate limit que depende do login.

| Rota | Path | O que mostra |
|---|---|---|
| `catalogo` | `/catalogo/**` | `methods` (GET e HEAD), rate limit por `ip` com `burst`, `AddResponseHeader`, `RemoveResponseHeader` e `transform.deny` |
| `parceiros` | `/parceiros/**` | rate limit por `header:X-Api-Key`, throttling sem fila, `RemoveRequestParameter`, `AddRequestHeader`, `RequestSize` e `timeouts` (1s para conectar, 5s para responder) |
| `produtos-leitura`, `produtos-escrita` | `/produtos/**` | a mesma URL com uma regra por método: `access`, limite e corpo diferentes para ler e para escrever |
| `legado` | `/legado/**` | `RewritePath`, `SetRequestHeader`, `RemoveRequestHeader`, `SetResponseHeader` e o `transform` inteiro (`target`, `deny`, `mapping` e `group`) |
| `cliente-resumo` | `/clientes/{id}/resumo` | variável no path, `SetPath`, `AddRequestParameter`, `transform.allow` com `mapping` e `mode: authenticated` |
| `estoque` | `/estoque/**` | `PrefixPath` e, com o Keycloak, sistema a sistema: `scope:` no `anyOf`, `audience` e rate limit por `client-id` |
| `relatorios` | `/relatorios/**` | `stripPrefix: 0`, rate limit da rota toda por hora (sem `key`), throttling com fila, `timeouts.response` maior que o global e, sem `access`, o `default-access` |
| `painel-cliente` | `/painel/clientes/{id}` | `aggregate`: três backends, com e sem `group`, `transform` em cada um e na resposta montada, e `timeout` |
| `campanha` | `/campanha/**` | `enabled: false`, só em `crd/` |

| Cenário | O `access` do mostruário |
|---|---|
| 0 | Nenhum. A `key` é `ip`, a rota toda (`relatorios`) ou o header (`parceiros`) |
| 1 | `group:` do AD. O `estoque` é de pessoas (`group:estoque-operadores`), porque no LDAP direto não há token |
| 2 e 4 | `role:` do client, `group:` do AD e `scope:` do token. `produtos-escrita` e `estoque` trocam o token (`audience`), e o `legado` repassa o token A |
| 3 | Como no 2, com `role:` no `legado`: o realm não tem o AD |
| 5 | `produtos-*` e `cliente-resumo` pelo LDAP direto (`channel: ldap`); o resto pelo Keycloak, como no 3 |

A `campanha` não está em `s3/`, porque o operator não publica rota desligada, nem em `properties/`, porque o v1 não olha o `enabled`: lá, uma rota com `enabled: false` continua no ar, e tirá-la do ar é apagá-la do arquivo.

## O application.yaml de cada cenário

- `gateway.security` muda com o cenário: `enabled: false` no 0, sem provedor; `ldap` no 1; `oidc` do Keycloak do 2 ao 5; `obo`, o app do gateway no Entra, do 3 ao 5; e, no 5, `additional-providers: [ldap]` com o `ldap` do diretório ao lado do `oidc`.
- O que vem entre `<` e `>` é do ambiente: `tenant-id` e `client-id` do `obo` e o `bucket` do S3. Troque no arquivo ou passe por variável (`GATEWAY_SECURITY_OBO_CLIENT_ID`, `GATEWAY_ROUTES_STORE_S3_BUCKET`).
- Segredo nenhum entra no arquivo: o cabeçalho de cada um lista as variáveis que o cenário pede (client secret do Keycloak, chave do cookie de sessão, senha do diretório, segredo do app no Entra). O 0 não pede nenhuma.
- `gateway.traffic.trusted-proxies` é o número de proxies na frente do gateway (o nginx-lb). É dele que o rate limit com `key: ip` tira o endereço do cliente.

## Regras entre os campos

Checadas no operator e no reload do gateway, para o v1 e o v2 aceitarem as mesmas rotas:

- `mode: public` não aceita mais nada no `access`.
- `channel: ldap` não tem token: recusa `forward-token: true`, `identity-provider`, `audience` e `obo`.
- `forward-token: true` liga o envio de token ao backend. `audience` e `obo` escolhem qual token vai, exigem `forward-token: true` e não convivem. Sem nenhum dos dois, vai o token A.
- `obo` exige `identity-provider` e de 1 a 16 scopes.
- `audience` e `obo` não valem em rota com `aggregate`: o mesmo token iria para todos os backends.

E o gateway não serve rota que pede o que ele não tem: canal que não está ativo, `identity-provider`, `audience` ou `obo` fora do canal keycloak, `obo` sem o app do gateway no Entra (`gateway.security.obo`).

## Implantação

- Um gateway anterior à ETT-1452 ignora os campos novos (no binding das properties e no `SnapshotCodec`) e serviria as rotas sem as checagens: atualize o gateway antes do operator.
- O `audience` usa a troca V2 do Keycloak 26.2 ou mais novo (o lab usa o 26.8.2), com um client scope opcional de mesmo nome do client do backend. O `obo` usa a troca V1 (features `token-exchange` e `admin-fine-grained-authz:v1`), o `storeToken` do provedor `microsoft` e o app do gateway no Entra.
- Os eventos de auditoria ganham três motivos: `wrong_identity_provider`, `identity_provider_login_required` e `token_exchange_failed` ([GATEWAY_DE_BORDA.md](../../GATEWAY_DE_BORDA.md)).

## No lab

- v2: `k8s/samples/routes-sso.yaml` (`sso-idp`, `sso-aud`, `sso-obo`) e, com `PROVIDER=keycloak-ldap`, `k8s/samples/routes-two-channels.yaml`.
- v1: as rotas `idp-route`, `audience-route` e `obo-route` de `gateway/src/main/resources/properties/application-standalone-sso.yaml`.
- Roteiro: seção 1.17 do [TESTE_SSO.md](../../TESTE_SSO.md), com o OBO no Entra de verdade; e2e em `make sso-test`, `make standalone-sso-test` e `make two-channels-test`.
