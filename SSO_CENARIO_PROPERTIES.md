# Cenário Properties (v1): rotas embarcadas na imagem, com SSO (Keycloak ou LDAP)

É o v1 standalone, sem operator nem CRD. As rotas, e o `access` de cada uma, ficam em `application-standalone-sso.yaml`, dentro da imagem nativa. Mudar uma rota ou uma política exige rebuild e rollout, como no v1 puro. Quem está em cada grupo continua dinâmico, porque isso é do AD ou do Keycloak. O login vem do Keycloak (`keycloak`) ou direto do diretório (`ldap`).

Os outros cenários: [`SSO_CENARIO_CRD.md`](SSO_CENARIO_CRD.md) e [`SSO_CENARIO_S3.md`](SSO_CENARIO_S3.md). O modelo de SSO e autorização está em [`SSO_E_AUTORIZACAO.md`](SSO_E_AUTORIZACAO.md), e o v1 sem SSO em [`TESTE_STANDALONE.md`](TESTE_STANDALONE.md).

## Como funciona

```
application-standalone-sso.yaml ── make load-images ──► imagem nativa (rotas + access)
                                                              │ rollout restart
                                                              ▼
browser / API ──► nginx-lb ──► gateway (profiles standalone-sso + keycloak | ldap) ──► backend
                                    │
                                    ▼
                         Keycloak (OIDC)  ou  diretório (LDAP/AD)
```

## O que já está configurado

| Peça | Arquivo | O que tem |
|---|---|---|
| Rotas e políticas, embarcadas | `gateway/src/main/resources/application-standalone-sso.yaml` | `store.type: properties`, 4 rotas com `access`, segurança e auditoria ligadas |
| Profile do provedor, embarcado | `application-keycloak.yaml` ou `application-ldap.yaml` | os mesmos dos cenários CRD e S3 |
| Deployment do gateway | `k8s/sso/gateway-standalone-keycloak.yaml` ou `k8s/sso/gateway-standalone-ldap.yaml` | ativa os profiles e passa a URL pública e os segredos |
| Service e Ingress | `k8s/gateway-standalone/service.yaml`, `ingress.yaml` | os do v1 |

**Profiles ativos:** `SPRING_PROFILES_ACTIVE=standalone-sso,keycloak` ou `SPRING_PROFILES_ACTIVE=standalone-sso,ldap`.

O `standalone-sso` liga a segurança sozinho. Sem o profile do provedor, o gateway **não sobe**: `gateway.security.public-base-url is required when gateway.security.enabled=true`. É de propósito. Com a segurança desligada, o gateway ignoraria o `access` e serviria as rotas abaixo sem login.

### Propriedades

As rotas (`gateway.routes.store.properties.routes`):

```yaml
- id: httpbin-route                      # só o grupo tools-httpbin
  path: /httpbin/**
  methods: [GET, POST]
  access: { any-of: [group:tools-httpbin] }
- id: anything-route                     # aberta, sem login
  path: /anything/**
  methods: [GET]
  access: { mode: public }
- id: me-route                           # sem access: vale o default-access (qualquer autenticado)
  path: /me/**
- id: admin-route                        # role do Keycloak OU grupo do diretório; backend recebe o token
  path: /admin/**
  access:
    any-of: [role:gateway-admin, group:tools-admins]
    forward-token: true
```

Todas apontam para `http://go-httpbin.platform.svc.cluster.local:8080`, que devolve os headers recebidos. A segurança (`gateway.security.…` e `gateway.audit.…`) é a mesma dos outros cenários:

| Propriedade (`gateway.…`) | `keycloak` | `ldap` |
|---|---|---|
| `security.enabled` / `audit.enabled` | `true` / `true` | `true` / `true` |
| `security.default-access` | `authenticated` | `authenticated` |
| `security.oidc.issuer` | `${public-base-url}/auth/realms/tools` | — |
| `security.oidc.internal-issuer` | `http://keycloak.platform.svc.cluster.local:8080/auth/realms/tools` | — |
| `security.oidc.client-id` / `audience` | `tools-gateway` | — |
| `security.ldap.url` | — | `ldap://openldap.platform.svc.cluster.local:389` |
| `security.ldap.bind-dn` | — | `cn=readonly,dc=tools,dc=local` |
| `security.ldap.user-search` / `group-search` | — | `ou=people,…` `(uid={0})` / `ou=tools,…` `(member={0})` |
| `security.internal.any-of` | `role:gateway-admin` | `group:tools-admins` |

O manifest passa `GATEWAY_SECURITY_PUBLIC_BASE_URL`, a chave de sessão (`SESSION_KEYS_0_*`) e `OIDC_CLIENT_SECRET` ou `LDAP_BIND_PASSWORD`, como na tabela do [cenário CRD](SSO_CENARIO_CRD.md#propriedades).

## Subir

Pré-requisito: o cluster ([`TESTE_SSO.md`](TESTE_SSO.md), seção 0) e as imagens (`make load-images`).

```bash
export HOST_PORT=18080 CLUSTER_NAME=dynamic-gateway-lab BASE=http://localhost:18080
export KUBECONFIG=$(k3d kubeconfig write $CLUSTER_NAME)

make standalone-sso-up                  # Keycloak + gateway v1 com standalone-sso,keycloak
PROVIDER=ldap make standalone-sso-up    # OpenLDAP + gateway v1 com standalone-sso,ldap
```

Se o operator do v2 estiver no mesmo namespace, o script avisa. O operator continua publicando a ConfigMap e sinalizando reloads, e o v1 ignora as duas coisas; fora do lab, v1 e v2 não dividem cluster.

## Testar

```bash
make standalone-sso-test                  # passed=21 failed=0
PROVIDER=ldap make standalone-sso-test    # passed=17 failed=0
```

| Rota | `access` | alice (`tools-httpbin`) | bob (sem grupos) | carol (`tools-admins`) |
|---|---|---|---|---|
| `/anything/**` | `mode: public` | 200 | 200 | 200 |
| `/me/**` | nenhum (`default-access`) | 200 | 200 | 200 |
| `/httpbin/**` | `any-of: [group:tools-httpbin]` | 200 | 403 | 200 |
| `/admin/**` | `any-of: [role:gateway-admin, group:tools-admins]` | 403 | 403 | 200 |

À mão:

```bash
curl -s -o /dev/null -w '%{http_code}\n' $BASE/anything/get                 # 200: pública
curl -s -o /dev/null -w '%{http_code}\n' $BASE/httpbin/get                  # 401: API sem credencial
curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' -H 'Accept: text/html' $BASE/httpbin/get   # 302 para o login
```

🌐 `http://localhost:18080/httpbin/headers` como `alice`: 200. Em `/admin/headers`, a `carol` entra e, no Keycloak, o backend recebe `Authorization: Bearer …`. No LDAP o backend recebe só os `X-Auth-Request-*`, porque não existe access token.

## Mudar quem acessa uma rota

1. Edite `access` (ou a rota) em `gateway/src/main/resources/application-standalone-sso.yaml`.
2. `./gradlew :gateway:test --tests 'com.example.gateway.GatewayProfilesTest'`. O teste falha se alguma chave do YAML não casar com uma propriedade, o que pega erro de digitação.
3. `make load-images` (~3 min, build nativo).
4. `kubectl -n platform rollout restart deployment/dynamic-gateway`. A tag `:local` não muda, então só pods novos pegam a imagem nova.

Sem rebuild dá para mudar só o comportamento das rotas **sem** `access`: `GATEWAY_SECURITY_DEFAULT_ACCESS=public` no Deployment deixa abertas as rotas que não declaram política. `POST /internal/routes/reload` não muda nada neste modo, porque o snapshot é estático.

## Em outro ambiente

O SSO se sobrescreve por variável de ambiente, sem rebuild, com as mesmas variáveis do [cenário CRD](SSO_CENARIO_CRD.md#em-outro-ambiente): URL pública, issuer e client do Keycloak, ou URL, bind DN, bases e filtros do AD. As rotas, não. Para apontar para backends reais, edite o `application-standalone-sso.yaml` e gere a imagem do ambiente.

## Cuidados

- **No LDAP só existem grupos.** `role:gateway-admin` nunca casa, e por isso o `admin-route` aceita também `group:tools-admins`. `forward-token` não tem efeito.
- **Não aplique os manifests do v2 (operator, CRD) por cima deste cenário** sem derrubá-lo antes (`make standalone-sso-down`). Seriam dois caminhos de rota competindo.
- **O limite de tentativas de login do LDAP é por réplica:** 5 por usuário × réplicas.

## Voltar

```bash
make standalone-sso-down    # gateway v1 sem SSO; remove Keycloak e OpenLDAP (mantém o Secret sso-secrets)
make sso-up                 # ou: volta para o cenário CRD (v2) com Keycloak
```
