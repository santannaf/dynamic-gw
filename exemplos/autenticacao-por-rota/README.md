# Autenticação por rota: exemplos por cenário

Exemplos da proposta de [AUTENTICACAO_POR_ROTA.pdf](../../AUTENTICACAO_POR_ROTA.pdf). Cada arquivo é um cenário de gateway em um store, com uma rota por estratégia que o cenário comporta. Nada aqui é carregado pelo gateway, pelo operator ou pelos scripts.

| Cenário | O gateway | Estratégias | Profiles do gateway (properties · crd · s3) | Novo |
|---|---|---|---|---|
| 1 | Faz o LDAP direto | PUBLIC, LDAP | `standalone-sso,ldap` · `ldap` · `s3,ldap` | Nada |
| 2 | Usa o Keycloak com LDAP | PUBLIC, LDAP | `standalone-sso,keycloak` · `keycloak` · `s3,keycloak` | `audience` |
| 3 | Usa o Keycloak com Identity Provider | PUBLIC, IDENTITY_PROVIDER, OBO | `standalone-sso,keycloak` · `keycloak` · `s3,keycloak` | `identity-provider`, `audience`, `obo` |
| 4 | Usa o Keycloak com Identity Provider e LDAP | PUBLIC, LDAP, IDENTITY_PROVIDER, OBO | `standalone-sso,keycloak` · `keycloak` · `s3,keycloak` | `identity-provider`, `audience`, `obo` |
| 5 | Faz o LDAP direto e usa o Keycloak com Identity Provider | PUBLIC, LDAP direto, IDENTITY_PROVIDER, OBO | os do cenário 3, com o canal `ldap` a mais | `additional-providers`, `channel` e o do cenário 3 |

O cenário 4 é o realm do lab de hoje. O 5 é o único com dois canais no mesmo gateway, e é a maior mudança: duas sessões, dois logins e a configuração dos dois provedores juntas.

## Pastas

- `properties/`: v1, rotas no YAML do gateway. Cada arquivo explica o cenário (realm, login, o que é novo) e traz a configuração do gateway que ele pede.
- `crd/`: v2 com o snapshot no ConfigMap, o default. Os `GatewayRoute` que o time escreve. `schema-access.yaml` tem o schema proposto do `access`.
- `s3/`: v2 com o snapshot no S3. O time escreve os mesmos `GatewayRoute` de `crd/`; aqui está o objeto que o operator grava em `s3://<bucket>/snapshots/routes.yaml`, no formato do `SnapshotCodec`.

Os três arquivos de um cenário têm as mesmas rotas, com os mesmos ids, o mesmo `access` e as mesmas políticas. As rotas também trazem rate limit (`ip` na pública, `user` nas autenticadas) e throttling, que já existem no gateway ([POLITICAS_DE_ROTA.md](../../POLITICAS_DE_ROTA.md)).

## Regras novas

Checadas no operator e no reload do gateway, para o v1 e o v2 aceitarem as mesmas rotas:

- `mode: public` não aceita mais nada no `access`.
- `channel: ldap` não tem token: recusa `forward-token`, `identity-provider`, `audience` e `obo`.
- `forward-token: true` liga o envio de token ao backend. `audience` e `obo` escolhem qual token vai, exigem `forward-token: true` e não convivem. Sem nenhum dos dois, vai o token A.
- `obo` exige `identity-provider` e de 1 a 16 scopes.
- `audience` e `obo` não valem em rota com `aggregate`: o mesmo token iria para todos os backends.

## Antes de implementar

- O gateway de hoje não recusa os campos novos: ignora o que não conhece (no binding das properties e no `SnapshotCodec`) e serviria as rotas sem as checagens novas. Na implantação, o gateway novo sobe antes do operator novo.
- O `hashAccess` do `GatewayRouteReloadService` só lê `mode`, `anyOf` e `forwardToken`. Sem os campos novos, mudar só o `audience` de uma rota não a recarrega.
- O `audience` usa a troca V2 do Keycloak 26.2 ou mais novo; o lab está no 26.1.
- Mudar o `access` mexe em `RouteAccess` (shared), `GatewayRouteAccess` e `RouteEntries.toAccess` (operator), `RoutePolicyRules`, `hashAccess` e o schema do CRD.
