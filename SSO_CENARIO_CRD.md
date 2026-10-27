# Cenário CRD: rotas por `GatewayRoute` com SSO (Keycloak ou LDAP)

É o v2 padrão. Cada time declara a sua rota, e quem pode usá-la, num `GatewayRoute`. O operator consolida tudo num snapshot na ConfigMap `gateway-routes`, e cada réplica do gateway aplica a mudança em segundos, sem restart. O login vem do Keycloak (`keycloak`) ou direto do diretório (`ldap`, OpenLDAP no lab no lugar do AD).

Os outros cenários: [`SSO_CENARIO_S3.md`](SSO_CENARIO_S3.md) (o mesmo fluxo, com o snapshot num bucket S3) e [`SSO_CENARIO_PROPERTIES.md`](SSO_CENARIO_PROPERTIES.md) (v1, rotas embarcadas na imagem). O modelo (SSO vs autorização, onde ver usuários e grupos no Keycloak) está em [`SSO_E_AUTORIZACAO.md`](SSO_E_AUTORIZACAO.md), e o roteiro completo no browser em [`TESTE_SSO.md`](TESTE_SSO.md).

## Como funciona

```
time ── kubectl apply ──► GatewayRoute (spec.access)
                                │  operator valida e consolida
                                ▼
                     ConfigMap gateway-routes  (snapshot com o access de cada rota)
                                │  watch em cada réplica: segundos
                                ▼
browser / API ──► nginx-lb ──► gateway (profile keycloak | ldap) ──► backend
                                │  login e sessão        ▲ X-Auth-Request-User/Groups/...
                                ▼
                     Keycloak (OIDC)  ou  diretório (LDAP/AD)
```

## O que já está configurado

| Peça | Arquivo | O que tem |
|---|---|---|
| Profile do provedor, embarcado na imagem | `gateway/src/main/resources/application-keycloak.yaml` ou `application-ldap.yaml` | segurança e auditoria ligadas, realm/client do Keycloak ou servidor e bases de busca do diretório |
| Store de rotas | `gateway/src/main/resources/application.yaml` | `gateway.routes.store.type: configmap` (default) |
| Deployment do gateway | `k8s/sso/gateway-keycloak.yaml` ou `k8s/sso/gateway-ldap.yaml` | ativa o profile e passa só a URL pública e os segredos |
| Rotas de exemplo | `k8s/samples/routes-sso.yaml` | 7 rotas `/sso/*`, uma política diferente em cada |
| CRD | `k8s/crd/gatewayroutes.platform.saca.pags.yaml` | `spec.access` validado no `kubectl apply` |
| Operator | `k8s/operator/deployment.yaml` | sem mudança: o operator não participa do SSO |

**Profiles ativos:** `SPRING_PROFILES_ACTIVE=keycloak` ou `SPRING_PROFILES_ACTIVE=ldap`.

### Propriedades

| Propriedade (`gateway.…`) | `keycloak` | `ldap` |
|---|---|---|
| `security.enabled` / `audit.enabled` | `true` / `true` | `true` / `true` |
| `security.default-access` (rota sem `access`) | `authenticated` | `authenticated` |
| `security.public-base-url` | variável de ambiente | variável de ambiente |
| `security.oidc.issuer` | `${public-base-url}/auth/realms/tools` | — |
| `security.oidc.internal-issuer` | `http://keycloak.platform.svc.cluster.local:8080/auth/realms/tools` | — |
| `security.oidc.client-id` / `audience` | `tools-gateway` / `tools-gateway` | — |
| `security.authorities.group-claims` → `group:` | `groups` | — |
| `security.authorities.role-claims` → `role:` | `realm_access.roles`, `resource_access.{client-id}.roles` | — |
| `security.ldap.url` | — | `ldap://openldap.platform.svc.cluster.local:389` |
| `security.ldap.bind-dn` | — | `cn=readonly,dc=tools,dc=local` |
| `security.ldap.user-search` | — | base `ou=people,dc=tools,dc=local`, filtro `(uid={0})` |
| `security.ldap.group-search` → `group:<cn>` | — | base `ou=tools,dc=tools,dc=local`, filtro `(member={0})` |
| `security.ldap.revalidate-after` | — | `5m` |
| `security.internal.any-of` (quem lê `/internal/routes`) | `role:gateway-admin` | `group:tools-admins` |
| `security.session.cookie-secure` | `true` | `true` |

Os segredos nunca ficam no profile, porque ele vai dentro da imagem. O manifest passa:

| Variável | `keycloak` | `ldap` | Para quê |
|---|---|---|---|
| `GATEWAY_SECURITY_PUBLIC_BASE_URL` | `http://localhost:<HOST_PORT>` | idem | redirect do login e, no Keycloak, o issuer |
| `GATEWAY_SECURITY_SESSION_KEYS_0_ID` / `_SECRET` | `k1` / Secret `sso-secrets` | idem | chave AES-256 do cookie de sessão |
| `GATEWAY_SECURITY_OIDC_CLIENT_SECRET` | Secret `sso-secrets` | — | client `tools-gateway` |
| `GATEWAY_SECURITY_LDAP_BIND_PASSWORD` | — | Secret `sso-secrets` | conta de serviço somente leitura |
| `GATEWAY_SECURITY_SESSION_COOKIE_SECURE` | `false` | `false` | **só no lab** (http); em produção remova |
| `GATEWAY_SECURITY_LDAP_REVALIDATE_AFTER` | — | `30s` | **só no lab**, para o e2e ver a revalidação |

## Subir

Pré-requisito: cluster e plataforma v2 no ar ([`TESTE_SSO.md`](TESTE_SSO.md), seção 0: `make cluster` e `make deploy`).

```bash
export HOST_PORT=18080 CLUSTER_NAME=dynamic-gateway-lab BASE=http://localhost:18080
export KUBECONFIG=$(k3d kubeconfig write $CLUSTER_NAME)

make sso-up        # Keycloak (realm tools) + gateway com o profile keycloak + rotas /sso/*
make ldap-up       # OpenLDAP (no lugar do AD) + gateway com o profile ldap + rotas /sso/*
```

Os dois imprimem os usuários e a senha do lab. Trocar de um para o outro só troca o gateway. O Keycloak não reinicia, e as sessões abertas continuam valendo.

| Usuário | Grupos | Role (só Keycloak) |
|---|---|---|
| `alice` | `tools-httpbin` | — |
| `bob` | — | — |
| `carol` | `tools-admins`, `tools-httpbin` | `gateway-admin` (herdado do grupo) |

## Testar

```bash
make sso-test      # passed=32 failed=0
make ldap-test     # passed=21 failed=0
```

À mão:

```bash
curl -s -o /dev/null -w '%{http_code}\n' $BASE/sso/group/get                       # 401: API sem credencial
curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' -H 'Accept: text/html' $BASE/sso/group/get
# 302 para o login (Keycloak: /_gateway/oauth2/authorization/keycloak; LDAP: /_gateway/login)
```

🌐 Abra `http://localhost:18080/sso/group/headers` numa janela anônima e entre como `alice`: 200, com `X-Auth-Request-User: alice`. Como `bob`, dá 403.

| Rota | `access` | alice | bob | carol |
|---|---|---|---|---|
| `/sso/public/**` | `mode: public` (sem login) | 200 | 200 | 200 |
| `/sso/auth/**` | nenhum: vale o `default-access` | 200 | 200 | 200 |
| `/sso/group/**` | `anyOf: [group:tools-httpbin]` | 200 | 403 | 200 |
| `/sso/admin/**` | `anyOf: [role:gateway-admin, group:tools-admins]` | 403 | 403 | 200 |
| `/sso/token/**` | `anyOf: [group:tools-httpbin]`, `forwardToken: true` | 200 | 403 | 200 |
| `/sso/rw/**` GET/HEAD | `anyOf: [group:tools-httpbin, group:tools-admins]` | 200 | 403 | 200 |
| `/sso/rw/**` POST/PUT/PATCH/DELETE | `anyOf: [group:tools-admins]` | 403 | 403 | 200 |
| `/anything/**`, `/httpbin/**` (do `make deploy`) | nenhum | 200 | 200 | 200 |

## Mudar quem acessa uma rota

A política mora no próprio `GatewayRoute`:

```yaml
apiVersion: platform.saca.pags/v1alpha1
kind: GatewayRoute
metadata:
  name: financeiro-relatorios
  namespace: platform
spec:
  path: /financeiro/relatorios/**
  targetUri: http://relatorios.financeiro.svc.cluster.local:8080
  stripPrefix: 2
  team: financeiro
  access:
    anyOf:
      - group:tools-financeiro-leitura     # grupo do AD/Keycloak; role:<nome> só no Keycloak
```

```bash
kubectl -n platform patch gwr sso-group --type merge -p '{"spec":{"access":{"anyOf":["group:tools-admins"]}}}'
```

Vale em segundos, sem restart. O operator publica um snapshot novo e sinaliza o reload; além disso, cada réplica observa a ConfigMap. O hash de "snapshot inalterado" inclui o `access`, então mudar só a política já dispara o reload. Combinações inválidas, como `mode: public` com `anyOf`, são recusadas no próprio `kubectl apply`. Quem está em cada grupo continua sendo decisão do IDM, no AD ou no Keycloak. A mudança chega no próximo refresh (Keycloak, até 5 min) ou na próxima revalidação (LDAP, `revalidate-after`).

## Em outro ambiente

Os profiles apontam para o lab. Fora dele, sobrescreva por variável de ambiente no Deployment, sem rebuild:

```bash
# Keycloak
GATEWAY_SECURITY_PUBLIC_BASE_URL=https://tools.empresa.com.br
GATEWAY_SECURITY_OIDC_ISSUER=https://sso.empresa.com.br/realms/tools          # igual ao `iss` dos tokens
GATEWAY_SECURITY_OIDC_INTERNAL_ISSUER=http://keycloak.sso.svc.cluster.local:8080/realms/tools
GATEWAY_SECURITY_OIDC_CLIENT_ID=tools-gateway      # e GATEWAY_SECURITY_OIDC_AUDIENCE, se mudar

# AD direto (provider ldap)
GATEWAY_SECURITY_PUBLIC_BASE_URL=https://tools.empresa.com.br
GATEWAY_SECURITY_LDAP_URL=ldaps://ad.corp.local:636
GATEWAY_SECURITY_LDAP_TRUSTED_CERTIFICATES=/etc/ssl/corp-ca.pem               # PEM da CA interna
GATEWAY_SECURITY_LDAP_BIND_DN=CN=svc-tools-gateway,OU=Services,DC=corp,DC=local
GATEWAY_SECURITY_LDAP_USER_SEARCH_BASE=OU=Users,DC=corp,DC=local
GATEWAY_SECURITY_LDAP_USER_SEARCH_FILTER=(sAMAccountName={0})
GATEWAY_SECURITY_LDAP_USERNAME_ATTRIBUTE=sAMAccountName
GATEWAY_SECURITY_LDAP_SUBJECT_ATTRIBUTE=objectGUID
GATEWAY_SECURITY_LDAP_GROUP_SEARCH_BASE=OU=Tools,OU=Groups,DC=corp,DC=local   # a OU da aplicação
GATEWAY_SECURITY_LDAP_GROUP_SEARCH_FILTER=(member:1.2.840.113556.1.4.1941:={0})   # inclui grupos aninhados
```

- **Segredos:** client secret ou senha da conta de serviço, e a chave de sessão (`openssl rand -base64 32`). Para rotacionar, ponha a nova em `KEYS_0` e mova a antiga para `KEYS_1`: a primeira cifra, todas decifram.
- **https:** tire o `GATEWAY_SECURITY_SESSION_COOKIE_SECURE=false` (o profile já usa `true`).
- **Admin:** `GATEWAY_SECURITY_INTERNAL_ANY_OF_0` troca quem lê `/internal/routes`.

## Cuidados

- **Sem o profile `keycloak` ou `ldap`, a segurança fica desligada.** O gateway ignora o `access` das rotas, e uma rota com `anyOf` fica aberta. Também deixa de remover os headers `X-Auth-Request-*`. Não publique rotas com `access` num gateway sem provedor.
- **Quem edita `GatewayRoute`, ou a ConfigMap `gateway-routes`, decide quem acessa o quê.** Restrinja isso por RBAC.
- **No LDAP só existem grupos.** `role:` nunca casa, e `forwardToken` não tem efeito (não há access token).
- **O limite de tentativas de login do LDAP é por réplica:** 5 por usuário × réplicas.

## Voltar

```bash
make sso-down      # gateway sem SSO, remove Keycloak/OpenLDAP e as rotas /sso/* (mantém o Secret sso-secrets)
```
