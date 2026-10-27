# SSO e autorização por rota: como funciona

Este documento explica a diferença entre **entrar** (SSO) e **poder usar uma rota** (autorização), mostra onde ver usuários, grupos e sessões no Keycloak, e traz um exemplo completo: deixar `/sso/token` só para a Carol. O roteiro de testes com todos os cenários está em [`TESTE_SSO.md`](TESTE_SSO.md), e a configuração pronta de cada cenário de rotas (CRD, S3, Properties) em [`SSO_CENARIO_CRD.md`](SSO_CENARIO_CRD.md), [`SSO_CENARIO_S3.md`](SSO_CENARIO_S3.md) e [`SSO_CENARIO_PROPERTIES.md`](SSO_CENARIO_PROPERTIES.md).

Os exemplos usam o ambiente local do roteiro: gateway em `http://localhost:18080`, Keycloak em `/auth`, realm `tools`.

---

## 1. Duas perguntas diferentes

| Pergunta                                    | Quem responde           | Onde se configura                                                                           |
|---------------------------------------------|-------------------------|---------------------------------------------------------------------------------------------|
| **Quem é você?** (autenticação, o SSO)      | Keycloak, uma vez só    | usuários, senhas e grupos no Keycloak (ou no AD que ele federa)                             |
| **Você pode usar esta rota?** (autorização) | Gateway, a cada request | bloco `access` de cada rota (`GatewayRoute` no v2, `application-standalone-sso.yaml` no v1) |

O SSO não libera as rotas. Ele só garante que a pessoa prove quem é **uma vez**. Depois disso, cada rota decide sozinha se aceita, olhando os grupos e roles que vieram do Keycloak.

| `access` da rota           | Quem entra                                                                              |
|----------------------------|-----------------------------------------------------------------------------------------|
| `mode: public`             | qualquer um, sem login                                                                  |
| sem bloco `access`         | qualquer usuário autenticado (default `gateway.security.default-access: authenticated`) |
| `anyOf: [group:X, role:Y]` | quem tiver pelo menos uma dessas autoridades                                            |

Por isso, numa lista como `/anything/**`, `/httpbin/**`, `/sso/admin/**`, `/sso/auth/**`, `/sso/group/**`, `/sso/public/**`, `/sso/rw/**` e `/sso/token/**`, cada rota tem a sua regra. As que não declaram `access` (como `/anything/**` e `/httpbin/**` no v2) aceitam qualquer usuário logado.

---

## 2. Como o SSO funciona para a Alice

```
1. Alice ─ GET /sso/token ─► Gateway: "não sei quem é" ─► redireciona para o Keycloak
2. Keycloak: Alice digita a senha (uma vez) → o Keycloak cria a sessão SSO dela
3. Keycloak ─ devolve um código ─► o Gateway troca o código por tokens e passa a saber:
      usuária = alice, grupos = [tools-httpbin]   → cria o cookie GW_SESSION
4. Gateway confere a regra da rota /sso/token → aceita (200) ou nega (403)
5. Alice abre /sso/group, /sso/auth, /httpbin… → ninguém pede senha de novo;
   cada rota só confere os grupos dela
6. Alice abre outra aplicação ligada ao mesmo Keycloak → também entra sem senha
```

Se a Alice recebe 403 em `/sso/token`, o SSO **funcionou**: ela está autenticada. Quem negou foi a regra da rota.

Onde fica a sessão:
- **No Keycloak**, a sessão mestre (cookie `KEYCLOAK_SESSION`, path `/auth/realms/tools`).
- **No browser**, o cookie `GW_SESSION` do gateway: cifrado (JWE), com o `sid` e o refresh token **dessa** sessão do Keycloak. Não existe sessão própria do gateway num banco ou num Redis.
- **Em cada réplica do gateway**, um cache em memória com a identidade da Alice. Ele vale até o access token expirar (5 min). Depois disso, a réplica faz um refresh no Keycloak, e é aí que mudanças de grupo e desligamentos chegam ao gateway, sem novo login.

O que o gateway recebe do Keycloak é o access token. A parte que importa para a autorização:

```json
{"preferred_username": "alice", "groups": ["tools-httpbin"], "aud": "tools-gateway", "typ": "Bearer"}
{"preferred_username": "carol", "groups": ["tools-admins", "tools-httpbin"], "realm_access": {"roles": ["gateway-admin"]}}
```

O gateway transforma isso em autoridades `group:tools-httpbin`, `group:tools-admins` e `role:gateway-admin`, e as compara com o `anyOf` da rota. O backend recebe a identidade pronta nos headers `X-Auth-Request-*`.

---

## 3. Onde ver no Keycloak

### Pelo console de administração

1. Abra `http://localhost:18080/auth/admin` e entre como `admin`. A senha está no Secret:
   ```bash
   kubectl -n platform get secret sso-secrets -o jsonpath='{.data.keycloak-admin-password}' | base64 -d; echo
   ```
2. No seletor de realm, no topo do menu lateral, troque de `master` para **tools** (aparece como "tools (local)").

| O que você quer ver                                           | Onde fica                                                                                                                                |
|---------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| Lista de usuários                                             | **Users** (busque `alice` ou `*`)                                                                                                        |
| Dados da Alice, senha, se está ativa                          | **Users → alice → Details / Credentials**                                                                                                |
| Grupos da Alice (entrar ou sair)                              | **Users → alice → Groups** (*Join group* / *Leave*)                                                                                      |
| Roles da Alice                                                | **Users → alice → Role mapping**; desmarque *Hide inherited roles* para ver também os que vêm de grupo                                   |
| Sessões abertas da Alice (e encerrar)                         | **Users → alice → Sessions** (menu ⋮ → *Sign out*)                                                                                       |
| Membros de um grupo                                           | **Groups → tools-httpbin → Members**                                                                                                     |
| Roles que um grupo dá                                         | **Groups → tools-admins → Role mapping** (é daqui que a Carol herda `gateway-admin`)                                                     |
| Quem tem um role direto                                       | **Realm roles → gateway-admin → Users in role**. Mostra só atribuições diretas, então a Carol não aparece aqui, porque herda pelo grupo. |
| Todas as sessões do realm                                     | **Sessions**                                                                                                                             |
| Histórico de logins, logouts e refreshes                      | **Events → User events**                                                                                                                 |
| Quem mudou o quê no Keycloak (ex.: colocou a Alice num grupo) | **Events → Admin events**                                                                                                                |
| **O token exatamente como o gateway vai receber**             | **Clients → tools-gateway → Client scopes → Evaluate**: escolha o usuário e abra *Generated access token*                                |

A última linha é a melhor forma de entender por que uma rota aceitou ou negou alguém: o `groups` e o `realm_access.roles` desse token são o que o gateway compara com o `anyOf`.

### Pelo terminal (kcadm)

```bash
export KUBECONFIG=$(k3d kubeconfig write dynamic-gateway-lab)
KC_ADMIN=$(kubectl -n platform get secret sso-secrets -o jsonpath='{.data.keycloak-admin-password}' | base64 -d)
kc() { kubectl -n platform exec deploy/keycloak -- /opt/keycloak/bin/kcadm.sh "$@" 2>/dev/null; }
kc config credentials --server http://localhost:8080/auth --realm master --user admin --password "$KC_ADMIN"

kc get users -r tools --fields username,email,enabled                       # usuários
ALICE=$(kc get users -r tools -q username=alice -q exact=true --fields id | jq -r '.[0].id')
kc get users/$ALICE/groups -r tools --fields name                            # grupos da alice
kc get users/$ALICE/sessions -r tools | jq '.[] | {ipAddress, clients}'      # sessões abertas

GROUP=$(kc get groups -r tools -q search=tools-httpbin --fields id | jq -r '.[0].id')
kc get groups/$GROUP/members -r tools --fields username                      # membros do grupo

CLIENT=$(kc get clients -r tools -q clientId=tools-gateway --fields id | jq -r '.[0].id')
kc get clients/$CLIENT/evaluate-scopes/generate-example-access-token -r tools -q userId=$ALICE -q scope=openid \
  | jq '{preferred_username, groups, realm_access}'                         # o token que o gateway recebe
```

---

## 4. Exemplo: só a Carol pode usar `/sso/token`

Hoje `/sso/token` exige `group:tools-httpbin`, e Alice e Carol estão nesse grupo, então as duas entram. Para restringir à Carol, a rota precisa exigir algo que **só a Carol tem**.

A rota não aceita regra por usuário (do tipo "só a carol"), e isso é proposital. Quem concede acesso é o IDM, colocando a pessoa num grupo. Se a regra citasse pessoas, cada mudança de acesso viraria uma alteração de rota, fora do controle do IDM e sem trilha no AD/Keycloak.

### Opção 1: rápida, com um grupo que já existe

`tools-admins` só tem a Carol.

```bash
kubectl -n platform get gwr sso-token -o wide        # ANYOF antes: ["group:tools-httpbin"]
kubectl -n platform patch gwr sso-token --type merge -p '{"spec":{"access":{"anyOf":["group:tools-admins"]}}}'
```

1. 🌐 Janela anônima, `http://localhost:18080/sso/token/headers` como **alice**. Ela faz login no Keycloak (o SSO funciona) e recebe **403**: página em branco, com o status visível em DevTools → Network.
2. 🌐 Ainda como alice, abra `/sso/group/headers`. Dá **200 sem pedir senha**: a mesma sessão vale para as outras rotas.
3. 🌐 Feche **todas** as janelas anônimas (no Firefox elas compartilham cookies), abra outra e entre em `/sso/token/headers` como **carol**. Dá **200**, e o backend recebe `X-Auth-Request-User: carol` e o `Authorization: Bearer …` (`forwardToken`).
4. A auditoria registra os dois lados:
   ```bash
   kubectl -n platform logs -l app.kubernetes.io/name=dynamic-gateway --prefix=false --tail=-1 \
     | grep '^{"@timestamp"' \
     | jq -c 'select(.gateway.route.id=="sso-token") | {quem: .user.name, resultado: .event.outcome, motivo: .event.reason, exigido: .gateway.access.required, casou: .gateway.access.matched}'
   # {"quem":"alice","resultado":"failure","motivo":"forbidden","exigido":["group:tools-admins"],"casou":null}
   # {"quem":"carol","resultado":"success","motivo":"allowed","exigido":["group:tools-admins"],"casou":"group:tools-admins"}
   ```

### Opção 2: a recomendada, com um grupo próprio da ferramenta

É o modelo que o IDM descreveu: um grupo por acesso, e quem entra nele é decidido fora do gateway.

1. Keycloak admin, realm **tools** → **Groups → Create group** → `tools-token`.
2. **Users → carol → Groups → Join group → `tools-token`**.
3. A rota passa a exigir esse grupo:
   ```bash
   kubectl -n platform patch gwr sso-token --type merge -p '{"spec":{"access":{"anyOf":["group:tools-token"]}}}'
   ```
4. A Carol precisa de um token novo para "ganhar" o grupo: faça logout em `http://localhost:18080/_gateway/logout` e login de novo, ou espere até 5 min pelo refresh. Resultado: Alice dá 403 e Carol dá 200.
5. **Dar acesso à Alice sem mexer na rota:** Users → alice → Groups → Join group → `tools-token`. Em até 5 min, no próximo refresh, ela passa a receber 200 sem logar de novo. É isso que o IDM faria no dia a dia (no AD, com o Keycloak federando).
6. Em **Events → Admin events** fica registrado quem colocou a Alice no grupo e quando. A auditoria do gateway mostra a partir de quando ela passou a acessar a rota.

### Voltar como estava

```bash
kubectl -n platform patch gwr sso-token --type merge -p '{"spec":{"access":{"anyOf":["group:tools-httpbin"]}}}'
```

---

## 5. Resumo de quem cuida de quê

| Mudança                           | Quem faz                  | Onde                                                                     | Quando vale                                   |
|-----------------------------------|---------------------------|--------------------------------------------------------------------------|-----------------------------------------------|
| Qual grupo uma rota exige         | time da rota / plataforma | `access` da `GatewayRoute`                                               | segundos (v2) ou redeploy (v1)                |
| Quem está em cada grupo           | IDM / suporte             | AD ou Keycloak                                                           | até 5 min, no próximo refresh, sem novo login |
| Derrubar o acesso de alguém       | IDM                       | desabilitar o usuário ou encerrar a sessão no Keycloak/AD                | até 5 min                                     |
| Saber quem acessou o quê e quando | SOC                       | auditoria do gateway (`AUDIT`) + eventos do Keycloak, ligados pelo `sid` | sempre                                        |
