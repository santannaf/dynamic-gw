# Roteiro de teste: SSO, autorização por rota e auditoria

Passo a passo do zero, com o resultado esperado em cada etapa. Para entender o modelo antes (SSO vs autorização, onde ver usuários e grupos no Keycloak), leia [`SSO_E_AUTORIZACAO.md`](SSO_E_AUTORIZACAO.md). A configuração de cada cenário de rotas, com Keycloak e com LDAP, está em [`SSO_CENARIO_CRD.md`](SSO_CENARIO_CRD.md), [`SSO_CENARIO_S3.md`](SSO_CENARIO_S3.md) e [`SSO_CENARIO_PROPERTIES.md`](SSO_CENARIO_PROPERTIES.md). Os comandos vão num terminal; 🌐 marca o que é feito no browser. As versões automatizadas são `make sso-test`, `make ldap-test`, `make s3-sso-test` e `make standalone-sso-test`.

1. [Ambiente](#0-ambiente)
2. [Provider `keycloak` (v2)](#1-provider-keycloak-v2)
3. [Provider `ldap` (v2)](#2-provider-ldap-v2)
4. [v1 standalone com SSO](#3-v1-standalone-com-sso)
5. [Limpeza](#4-limpeza)
6. [Problemas comuns](#problemas-comuns)

## Onde mora a sessão

- **Provider `keycloak`**: o SSO é o do Keycloak. O login acontece lá, e a sessão mestre (cookie `KEYCLOAK_SESSION` em `/auth/realms/tools`) também. O cookie `GW_SESSION` do gateway carrega o `sid` e o refresh token **dessa** sessão. Cada réplica revalida no Keycloak quando o cache local expira (a cada 5 min por padrão). Logout no gateway encerra a sessão no Keycloak; derrubar a sessão no Keycloak tira o acesso no gateway no próximo refresh. Qualquer outro client do realm aproveita o mesmo login.
- **Provider `ldap`**: não há Keycloak. A sessão é do gateway (cookie com o DN do usuário), revalidada no diretório. O SSO vale para todos os tools atrás do gateway, mas não para aplicações fora dele.

---

## 0. Ambiente

### 0.1 Terminal

Em todo terminal novo:

```bash
cd ~/Projetos/dynamic-gateway-control-plane
export HOST_PORT=18080                    # nesta máquina a 8080 é do cluster k3d-lab
export CLUSTER_NAME=dynamic-gateway-lab
export BASE=http://localhost:$HOST_PORT
```

### 0.2 Cluster

**A) O cluster de teste já existe** (`k3d cluster list` mostra `dynamic-gateway-lab`):

```bash
export KUBECONFIG=$(k3d kubeconfig write $CLUSTER_NAME)   # vale só para este terminal
kubectl -n platform get pods
```

**B) Do zero absoluto** (~10 min, a maior parte é o build nativo):

```bash
k3d cluster delete $CLUSTER_NAME 2>/dev/null
make cluster        # cria o cluster mapeando $HOST_PORT; ATENÇÃO: troca o contexto atual do kubectl
make deploy         # build nativo no Docker + import + plataforma v2 (CRD, operator, gateway)
export KUBECONFIG=$(k3d kubeconfig write $CLUSTER_NAME)
```

Depois do teste, para devolver o kubectl dos outros terminais ao cluster de sempre: `kubectl config use-context k3d-lab`.

### 0.3 Subir o SSO com Keycloak

```bash
make sso-up
```

Isso sobe o Keycloak com o realm `tools`, o gateway com `provider=keycloak` + auditoria, as rotas de exemplo `/sso/*`, o go-httpbin e o nginx-lb. No fim ele imprime os usuários e a senha. Para guardar as senhas em variáveis:

```bash
export PASSWORD=$(kubectl -n platform get secret sso-secrets -o jsonpath='{.data.demo-password}' | base64 -d)
export KC_ADMIN=$(kubectl -n platform get secret sso-secrets -o jsonpath='{.data.keycloak-admin-password}' | base64 -d)
echo "usuarios: $PASSWORD   admin do Keycloak: $KC_ADMIN"
```

| Usuário              | Grupos                          | Role                               | Uso no teste                            |
|----------------------|---------------------------------|------------------------------------|-----------------------------------------|
| `alice`              | `tools-httpbin`                 | —                                  | usuária comum de um tool                |
| `bob`                | —                               | —                                  | autenticado, sem acesso a nada restrito |
| `carol`              | `tools-admins`, `tools-httpbin` | `gateway-admin` (herdado do grupo) | admin                                   |
| `tools-cli` (client) | —                               | `gateway-admin`                    | conta de serviço (M2M)                  |

Para **recomeçar do zero sem recriar o cluster** (o realm é reimportado e todas as sessões caem):

```bash
kubectl -n platform rollout restart deployment/keycloak
kubectl -n platform rollout status deployment/keycloak
```

### 0.4 Conferência

```bash
kubectl -n platform get pods
# keycloak, dynamic-gateway (2 pods), gateway-route-operator, go-httpbin e nginx-lb: Running

kubectl -n platform get gwr -o wide
# rotas sso-*; colunas ACCESS e ANYOF mostram a política de cada uma

curl -s $BASE/auth/realms/tools/.well-known/openid-configuration | jq -r .issuer
# http://localhost:18080/auth/realms/tools  (o mesmo issuer que o gateway valida)
```

Dicas de browser:
- Use sempre `localhost`, nunca `127.0.0.1`: cookies são por host.
- No Firefox todas as janelas anônimas compartilham cookies. Para trocar de usuário, feche **todas** as janelas anônimas antes de abrir outra.

---

## 1. Provider `keycloak` (v2)

O que esperar de cada rota:

| Rota                               | `access`                                          | alice        | bob | carol        |
|------------------------------------|---------------------------------------------------|--------------|-----|--------------|
| `/sso/public/**`                   | `mode: public`                                    | 200          | 200 | 200          |
| `/sso/auth/**`                     | sem `access` (default `authenticated`)            | 200          | 200 | 200          |
| `/sso/group/**`                    | `anyOf: [group:tools-httpbin]`                    | 200          | 403 | 200          |
| `/sso/admin/**`                    | `anyOf: [role:gateway-admin, group:tools-admins]` | 403          | 403 | 200          |
| `/sso/token/**`                    | `group:tools-httpbin` + `forwardToken: true`      | 200 + Bearer | 403 | 200 + Bearer |
| `/sso/rw/**` GET/HEAD              | `tools-httpbin` ou `tools-admins`                 | 200          | 403 | 200          |
| `/sso/rw/**` POST/PUT/PATCH/DELETE | `tools-admins`                                    | 403          | 403 | 200          |

Um 403 aparece como página em branco: o status está no DevTools (F12 → Network).

### 1.1 Sem sessão

```bash
curl -s -i $BASE/sso/auth/headers | grep -i -E '^HTTP|www-authenticate'
# HTTP/1.1 401 Unauthorized
# Www-Authenticate: Bearer realm="dynamic-gateway"

curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' -H 'Accept: text/html' $BASE/sso/group/headers
# 302 http://localhost:18080/_gateway/oauth2/authorization/keycloak   (browser vai pro login)

curl -s -o /dev/null -w '%{redirect_url}\n' $BASE/_gateway/oauth2/authorization/keycloak | tr '&' '\n' | grep -E 'code_challenge_method|redirect_uri'
# code_challenge_method=S256 (PKCE) e redirect_uri do gateway

curl -s -H 'X-Auth-Request-User: admin' $BASE/sso/public/headers | jq '.headers["X-Auth-Request-User"]'
# null  (header de identidade forjado nunca chega ao backend)
```

### 1.2 Login da alice

🌐 Numa janela anônima, abra `http://localhost:18080/sso/group/headers`. Você cai na tela de login do Keycloak (realm "tools (local)"). Entre como `alice` com `$PASSWORD`.

Esperado: volta sozinho para `/sso/group/headers` e mostra o JSON do httpbin com o que chegou no backend.
- `X-Auth-Request-User: alice`, `X-Auth-Request-Email`, `X-Auth-Request-Subject` e `X-Auth-Request-Groups: tools-httpbin` foram injetados pelo gateway.
- O header `Cookie` **não** tem `GW_SESSION`. Os outros cookies de `localhost` (Jenkins, pgAdmin…) aparecem porque o browser não separa cookies por porta.

🌐 Em F12 → Storage/Application → Cookies, o `GW_SESSION` é um JWE (5 partes separadas por ponto). Só o header é legível:

```bash
echo '<cole a primeira parte do cookie>' | base64 -d
# {"kid":"k1","enc":"A256GCM","alg":"dir"}
```

### 1.3 Quem o gateway acha que você é

🌐 `http://localhost:18080/_gateway/whoami` mostra `user: alice`, `method: session`, `authorities: ["group:tools-httpbin"]` e `expiresAt` (fim do access token, ~5 min). Quando esse horário passa, o próximo request renova no Keycloak sem você perceber.

### 1.4 O SSO é o do Keycloak

🌐 Na mesma janela, abra `http://localhost:18080/auth/realms/tools/account`. A conta da alice abre **sem pedir senha**: é a mesma sessão do Keycloak, aproveitada por outro client do realm.

🌐 Admin do Keycloak: `http://localhost:18080/auth/admin`, login `admin` / `$KC_ADMIN`. Troque para o realm **tools** (canto superior esquerdo) → **Sessions**. Aparece a sessão da alice com os clients `tools-gateway` e `account-console`. O ID dessa sessão é o `sid` que o gateway grava na auditoria (passo 1.10).

### 1.5 Leitura vs escrita

🌐 `http://localhost:18080/sso/rw/get` → 200.

🌐 No console do DevTools:

```js
fetch('/sso/rw/post', {method: 'POST'}).then(r => r.status)   // 403: escrever exige tools-admins
```

São duas `GatewayRoute` com o mesmo path, separadas por `methods`.

### 1.6 Política por rota, dinâmica

🌐 Abra `/sso/auth/headers` → 200 (qualquer autenticado).

```bash
kubectl -n platform patch gwr sso-auth --type merge -p '{"spec":{"access":{"anyOf":["group:tools-admins"]}}}'
```

🌐 Recarregue em 1-2 s → **403**, sem restart e sem novo login. O operator publicou um snapshot novo e as réplicas recarregaram.

```bash
kubectl -n platform get gwr sso-auth -o wide                                          # ANYOF agora mostra tools-admins
kubectl -n platform patch gwr sso-auth --type json -p '[{"op":"remove","path":"/spec/access"}]'
```

🌐 Recarregue → 200 de novo.

O CRD também barra política inválida no próprio `apply`:

```bash
cat <<'EOF' | kubectl apply --dry-run=server -f -
apiVersion: platform.saca.pags/v1alpha1
kind: GatewayRoute
metadata: {name: invalida, namespace: platform}
spec: {path: /x/**, targetUri: http://x, access: {mode: public, anyOf: [group:tools-httpbin]}}
EOF
# The GatewayRoute "invalida" is invalid: spec.access: ... access.mode=public cannot be combined with a non-empty access.anyOf
```

### 1.7 bob e carol

🌐 Feche todas as janelas anônimas e abra uma nova. Entre por `http://localhost:18080/sso/auth/headers` como `bob` → 200. Depois abra `/sso/group/headers` → **403**.

🌐 Feche tudo de novo e entre como `carol` por `http://localhost:18080/sso/admin/headers` → 200. O JSON mostra `X-Auth-Request-Roles: gateway-admin`. Abra `/sso/token/headers`: o backend recebe também `Authorization: Bearer eyJ…` (`forwardToken`). Em `/internal/routes` aparece a tabela de rotas com o `access` de cada uma (só admin vê; para a alice é 403).

### 1.8 Conta de serviço (M2M)

```bash
CLI_SECRET=$(kubectl -n platform get secret sso-secrets -o jsonpath='{.data.tools-cli-client-secret}' | base64 -d)
TOKEN=$(curl -s -d grant_type=client_credentials -d client_id=tools-cli --data-urlencode client_secret=$CLI_SECRET \
  $BASE/auth/realms/tools/protocol/openid-connect/token | jq -r .access_token)

curl -s -H "Authorization: Bearer $TOKEN" $BASE/sso/auth/headers | jq '.headers | {user: ."X-Auth-Request-User", client: ."X-Auth-Request-Client-Id", auth: .Authorization}'
# {"user":["service-account-tools-cli"],"client":["tools-cli"],"auth":null}
# auth null: o token era para o gateway e não é repassado (só com forwardToken)

curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" $BASE/sso/group/headers   # 403: sem o grupo
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" $BASE/internal/routes     # 200: tem gateway-admin
```

Um token válido de outro realm/audiência não serve:

```bash
FOREIGN=$(curl -s -d grant_type=password -d client_id=admin-cli -d username=admin --data-urlencode password=$KC_ADMIN \
  $BASE/auth/realms/master/protocol/openid-connect/token | jq -r .access_token)
curl -s -i -H "Authorization: Bearer $FOREIGN" $BASE/sso/auth/headers | grep -i -E '^HTTP|www-authenticate'
# 401 + error="invalid_token"
```

### 1.9 Várias réplicas, uma sessão

Copie o valor do `GW_SESSION` da alice no DevTools:

```bash
COOKIE='cole-o-valor-aqui'
kubectl -n platform scale deployment/dynamic-gateway --replicas=3
kubectl -n platform rollout status deployment/dynamic-gateway
for i in $(seq 1 6); do curl -s -o /dev/null -D - -b "GW_SESSION=$COOKIE" $BASE/sso/group/get | grep -i -E '^HTTP|^x-upstream'; done
# todas 200; o X-Upstream (pod que atendeu) varia
kubectl -n platform scale deployment/dynamic-gateway --replicas=2
```

A réplica nova não tinha a sessão em cache: fez **um** refresh no Keycloak e passou a servir do cache. Esse refresh aparece no admin do Keycloak em **Events → User events** como `REFRESH_TOKEN` da alice.

### 1.10 Auditoria (quem, o quê, quando)

```bash
kubectl -n platform logs -l app.kubernetes.io/name=dynamic-gateway --prefix=false --tail=-1 \
  | grep '^{"@timestamp"' \
  | jq -c '{quando: ."@timestamp", acao: .event.action, resultado: .event.outcome, motivo: .event.reason, quem: .user.name, rota: .gateway.route.id, status: .http.response.status_code, sid: .gateway.auth.sid}' \
  | tail -20
```

Confira:
- O `auth.login` da alice, os `route.access` com `allowed` e o 403 do bob com `forbidden`.
- O 401 do token estrangeiro com `invalid_token`.
- O `sid` é o mesmo ID de sessão que aparece no Keycloak (passo 1.4).

```bash
kubectl -n platform logs -l app.kubernetes.io/name=dynamic-gateway --prefix=false --tail=-1 | grep '^{"@timestamp"' | grep -c -e eyJ -e "$PASSWORD"
# 0: nenhum token ou senha na auditoria

kubectl -n platform logs deployment/keycloak | grep -E 'type="(LOGIN|LOGOUT|REFRESH_TOKEN)"' | tail -5
# o outro lado da trilha: eventos do Keycloak com o mesmo sessionId
```

### 1.11 O IDM tira a alice do grupo

Para ver o efeito rápido, reduza antes o tempo de vida do access token. No admin do realm **tools** → **Realm settings → Tokens → Access Token Lifespan = 1 minute** → Save. Depois faça login da alice de novo, porque os tokens antigos ainda são de 5 min.

1. Admin → **Users → alice → Groups** → selecione `tools-httpbin` → **Leave**.
2. 🌐 Como alice, `/sso/group/headers` continua 200 enquanto o token atual vale. Em até ~1 min (ou 5, sem o ajuste acima) passa a dar **403**, sem novo login. `/sso/auth/headers` continua 200.
3. Para desfazer: **Users → alice → Groups → Join group → tools-httpbin**. No próximo refresh volta o 200.

### 1.12 Desligamento: derrubar a sessão no Keycloak

1. Admin → **Users → alice → Sessions** → menu ⋮ → **Sign out**. Para simular um desligamento, também dá para desmarcar **Enabled** no usuário.
2. 🌐 No próximo refresh (até o fim do access token), a alice volta para a tela de login. Na auditoria o request aparece com `motivo: session_expired`.

### 1.13 Logout pelo gateway

🌐 `http://localhost:18080/_gateway/logout` encerra a sessão no gateway e no Keycloak. O browser passa pelo logout do Keycloak e volta para `http://localhost:18080/`, que dá 404 porque não existe rota na raiz (esperado). Se o Keycloak pedir confirmação, é porque a réplica que atendeu o logout não tinha o ID token em cache. Abrir `/sso/group/headers` de novo pede senha.

### 1.14 Borda

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST $BASE/internal/routes/reload
# 403: só o operator, de dentro do cluster, pode recarregar
```

### 1.15 Tudo de uma vez

```bash
make sso-test     # passed=32 failed=0
```

---

## 2. Provider `ldap` (v2)

O gateway faz o login sozinho, direto no diretório. O OpenLDAP faz o papel do AD: OU `tools` com os grupos de acesso, usuários em `ou=people`, e um grupo `finance` fora da OU da aplicação.

```bash
make ldap-up      # OpenLDAP + gateway com provider=ldap (o Keycloak continua no ar, sem uso)
```

1. 🌐 Janela anônima nova (feche as outras): `http://localhost:18080/sso/group/headers` vai para a **tela de login do próprio gateway** (`/_gateway/login`). Entre como `alice`.
2. O backend recebe `X-Auth-Request-User: alice` e `X-Auth-Request-Groups: tools-httpbin`. O `finance` **não** aparece, porque fica fora de `ou=tools`.
3. Senha errada volta para `?error` com a mesma mensagem para usuário inexistente. Errar várias vezes seguidas o mesmo usuário termina em **429**. O limite é por réplica: com 2 réplicas, até ~10 tentativas na janela de 5 min.
4. `bob` → 403 em `/sso/group/headers`. `carol` → 200 em `/internal/routes` (aqui o admin é o grupo `tools-admins`).
5. O `Authorization` não é interpretado neste modo:

   ```bash
   curl -s -H 'Authorization: Bearer token-do-backend' $BASE/sso/public/headers | jq -r '.headers.Authorization[0]'
   # Bearer token-do-backend  (vai intacto para o backend)
   ```

6. **Revalidação**: tire a alice do grupo direto no diretório e espere 30 s. No ambiente local o `revalidate-after` é 30s; o default é 5m.

   ```bash
   LDAP_ADMIN=$(kubectl -n platform get secret sso-secrets -o jsonpath='{.data.ldap-admin-password}' | base64 -d)
   printf 'dn: cn=tools-httpbin,ou=tools,dc=tools,dc=local\nchangetype: modify\ndelete: member\nmember: uid=alice,ou=people,dc=tools,dc=local\n' \
     | kubectl -n platform exec -i deployment/openldap -- ldapmodify -x -H ldap://localhost -D cn=admin,dc=tools,dc=local -w "$LDAP_ADMIN"
   ```

   🌐 A alice passa a levar 403 em `/sso/group/headers`, em qualquer réplica, sem novo login. Para devolver, troque `delete: member` por `add: member`.

7. 🌐 `http://localhost:18080/_gateway/logout` volta para `/_gateway/login?logout`.
8. A auditoria mostra os `auth.login` (inclusive as falhas, com o usuário digitado e nunca a senha) e os `throttled`.

```bash
make ldap-test    # passed=21 failed=0
```

Para voltar ao Keycloak: `make sso-up`.

---

## 3. v1 standalone com SSO

O mesmo SSO sem operator nem CRD. As rotas e o `access` de cada uma estão em `gateway/src/main/resources/application-standalone-sso.yaml`, embarcado na imagem: mudar exige rebuild e rollout, como no v1 puro.

```bash
make standalone-sso-up          # troca o gateway do cluster pelo v1 (PROVIDER=ldap também funciona)
```

| Rota           | Política                                                                  | alice | bob | carol                         |
|----------------|---------------------------------------------------------------------------|-------|-----|-------------------------------|
| `/anything/**` | `mode: public`                                                            | 200   | 200 | 200                           |
| `/me/**`       | sem `access` (default `authenticated`)                                    | 200   | 200 | 200                           |
| `/httpbin/**`  | `any-of: [group:tools-httpbin]`                                           | 200   | 403 | 200                           |
| `/admin/**`    | `any-of: [role:gateway-admin, group:tools-admins]`, `forward-token: true` | 403   | 403 | 200 (backend recebe o Bearer) |

🌐 Repita o login em `http://localhost:18080/httpbin/headers`. A sessão do Keycloak continua valendo, então se você já estava logado entra sem senha.

```bash
make standalone-sso-test        # passed=21 failed=0
make sso-up                     # volta para o v2 com SSO
```

---

## 4. Limpeza

```bash
make sso-down                              # gateway sem SSO; Keycloak e OpenLDAP removidos (Secret sso-secrets fica)
k3d cluster delete dynamic-gateway-lab     # remove o cluster de teste inteiro
kubectl config use-context k3d-lab         # se o `make cluster` tiver trocado seu contexto
```

---

## Problemas comuns

| Sintoma                                   | Causa                                                                                                              | O que fazer                                                                                                                 |
|-------------------------------------------|--------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------|
| "Login failed." depois de digitar a senha | O login começou em `127.0.0.1` e voltou em `localhost` (cookies são por host), ou passou de 5 min na tela de login | Recomece pela URL protegida usando sempre `localhost`                                                                       |
| Voltou para o login sem ter feito logout  | O Keycloak reiniciou (modo dev perde as sessões) ou a sessão foi derrubada                                         | Esperado: faça login de novo                                                                                                |
| Página em branco                          | É um 403 (sem corpo)                                                                                               | Confira o status no DevTools → Network                                                                                      |
| Usuário "errado" numa janela anônima      | No Firefox as janelas anônimas compartilham cookies                                                                | Feche todas antes de trocar de usuário                                                                                      |
| `ImagePullBackOff` no gateway/operator    | Imagem não chegou ao k3d                                                                                           | `make deploy` de novo; o build já usa `--provenance=false`, sem o qual o `k3d image import` descarta a imagem nesta máquina |
| Keycloak em `OOMKilled`                   | Modo dev precisa de ~2 GiB                                                                                         | Já configurado em `k8s/sso/keycloak.yaml`; confira a memória livre do Docker                                                |
| `kubectl` mexendo no cluster errado       | Contexto padrão é outro                                                                                            | `export KUBECONFIG=$(k3d kubeconfig write dynamic-gateway-lab)` no terminal do teste                                        |
