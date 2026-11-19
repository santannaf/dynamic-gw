# Autenticação por rota: exemplos por cenário

Exemplos de [AUTENTICACAO_POR_ROTA.pdf](../../AUTENTICACAO_POR_ROTA.pdf), implementado na ETT-1452. Cada pasta é um cenário de gateway em um store, com a configuração do gateway (`application.yaml`), as rotas (`rotas.yaml`, uma por estratégia que o cenário comporta) e um desenho por estratégia. Nada aqui é carregado pelo gateway, pelo operator ou pelos scripts: as rotas que o lab usa estão na seção [No lab](#no-lab).

| Cenário | O gateway | Estratégias | Profiles do gateway (properties · crd · s3) | Campos da ETT-1452 |
|---|---|---|---|---|
| 1 | Faz o LDAP direto | PUBLIC, LDAP | `standalone-sso,ldap` · `ldap` · `s3,ldap` | Nenhum |
| 2 | Usa o Keycloak com LDAP | PUBLIC, LDAP | `standalone-sso,keycloak` · `keycloak` · `s3,keycloak` | `audience` |
| 3 | Usa o Keycloak com Identity Provider | PUBLIC, IDENTITY_PROVIDER, OBO | `standalone-sso,keycloak` · `keycloak` · `s3,keycloak` | `identity-provider`, `audience`, `obo` |
| 4 | Usa o Keycloak com Identity Provider e LDAP | PUBLIC, LDAP, IDENTITY_PROVIDER, OBO | `standalone-sso,keycloak` · `keycloak` · `s3,keycloak` | `identity-provider`, `audience`, `obo` |
| 5 | Faz o LDAP direto e usa o Keycloak com Identity Provider | PUBLIC, LDAP direto, IDENTITY_PROVIDER, OBO | `standalone-sso,keycloak-ldap` · `keycloak-ldap` · `s3,keycloak-ldap` | `additional-providers`, `channel` e os do cenário 3 |

O cenário 4 é o realm do lab. O 5 é o único com dois canais no mesmo gateway: duas sessões (`GW_SESSION` e `GW_SESSION_LDAP`), dois logins e a configuração dos dois provedores juntas.

## Desenhos

Cada pasta de cenário tem, ao lado dos YAML, um PNG por estratégia: `desenho-<n>-<estratégia>.png`. O desenho mostra o sistema do cenário, e o que a estratégia não usa fica em cinza claro. O caminho da requisição vem numerado, passando pelas etapas do gateway (acesso, rate limit, throttling, token do backend e proxy). Embaixo aparece de onde vêm as rotas naquele store: o `rotas.yaml` na imagem (properties) ou o operator gravando o snapshot no ConfigMap (crd) ou no S3 (s3). Ao lado ficam o passo a passo, a rota como está no `rotas.yaml` da pasta, o que o backend recebe e as recusas. Exemplo: [`crd/cenario-4-keycloak-idp-e-ldap/desenho-4-obo.png`](crd/cenario-4-keycloak-idp-e-ldap/desenho-4-obo.png).

| Cenário | Desenhos na pasta do cenário, em cada store |
|---|---|
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

Num cenário, os três `rotas.yaml` têm as mesmas rotas, com os mesmos ids, o mesmo `access` e as mesmas políticas, e os três `application.yaml` só mudam em `gateway.routes.store`. As rotas também trazem rate limit (`ip` na pública, `user` nas autenticadas) e throttling ([POLITICAS_DE_ROTA.md](../../POLITICAS_DE_ROTA.md)).

## O application.yaml de cada cenário

- `gateway.security` muda com o cenário: `ldap` no 1; `oidc` do Keycloak do 2 ao 5; `obo`, o app do gateway no Entra, do 3 ao 5; e, no 5, `additional-providers: [ldap]` com o `ldap` do diretório ao lado do `oidc`.
- O que vem entre `<` e `>` é do ambiente: `tenant-id` e `client-id` do `obo` e o `bucket` do S3. Troque no arquivo ou passe por variável (`GATEWAY_SECURITY_OBO_CLIENT_ID`, `GATEWAY_ROUTES_STORE_S3_BUCKET`).
- Segredo nenhum entra no arquivo: o cabeçalho de cada um lista as variáveis que o cenário pede (client secret do Keycloak, chave do cookie de sessão, senha do diretório, segredo do app no Entra).
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
