# SSO e autorização por rota: como funciona

Este documento explica a diferença entre **entrar** (SSO) e **poder usar uma rota** (autorização), de onde vêm usuários, grupos e roles, onde ver tudo no Keycloak, e traz um exemplo completo: deixar `/sso/token` só para a Carol. No fim estão o modelo de roles e o passo a passo para ligar o Keycloak no AD de verdade. O roteiro de testes com todos os cenários está em [`TESTE_SSO.md`](TESTE_SSO.md), e a configuração pronta de cada cenário de rotas (CRD, S3, Properties) em [`SSO_CENARIO_CRD.md`](SSO_CENARIO_CRD.md), [`SSO_CENARIO_S3.md`](SSO_CENARIO_S3.md) e [`SSO_CENARIO_PROPERTIES.md`](SSO_CENARIO_PROPERTIES.md).

Os exemplos usam o ambiente local do roteiro: gateway em `http://localhost:18080`, Keycloak em `/auth`, realm `tools`, e um OpenLDAP no lugar do AD.

---

## 1. Duas perguntas diferentes

| Pergunta                                    | Quem responde           | Onde se configura                                                                           |
|---------------------------------------------|-------------------------|---------------------------------------------------------------------------------------------|
| **Quem é você?** (autenticação, o SSO)      | Keycloak, uma vez só    | usuários, senhas e grupos no AD (no lab, o OpenLDAP), que o Keycloak federa                 |
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
2. Keycloak: Alice digita a senha (uma vez). O Keycloak confere a senha com um bind no
   diretório (a senha nunca fica no Keycloak) e cria a sessão SSO dela
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
- **Em cada réplica do gateway**, um cache em memória com a identidade da Alice. Ele vale até o access token expirar (5 min). Depois disso, a réplica faz um refresh no Keycloak, e é aí que mudanças de grupo e desligamentos chegam ao gateway, sem novo login. O Keycloak guarda o que leu do diretório por até 1 min (*cache policy* `MAX_LIFESPAN`), então uma mudança no AD chega às rotas em até ~6 min.
- **Logout pelo gateway** vale na hora em todas as réplicas: a que atende o logout avisa as outras (pelo Service headless, `gateway.security.session.peers`), e cada uma passa a recusar aquela sessão, mesmo que ela esteja no cache ou que alguém tenha guardado uma cópia do cookie.

O que o gateway recebe do Keycloak é o access token. A parte que importa para a autorização:

```json
{"preferred_username": "alice", "groups": ["tools-httpbin"], "aud": "tools-gateway", "typ": "Bearer"}
{"preferred_username": "carol", "groups": ["tools-admins", "tools-httpbin"]}
{"preferred_username": "dave",  "groups": ["gateway-admins"], "resource_access": {"tools-gateway": {"roles": ["gateway-admin"]}}}
```

O gateway transforma isso em autoridades (`group:tools-httpbin`, `group:tools-admins`, `group:gateway-admins`, `role:gateway-admin`) e as compara com o `anyOf` da rota. O backend recebe a identidade pronta nos headers `X-Auth-Request-*`.

Só chegam grupos da OU da aplicação (`ou=tools`): a Alice também está em `finance`, de outra OU, e esse grupo nunca aparece no token. Roles, só as do client `tools-gateway`; o token não traz `realm_access`.

---

## 3. Onde ver no Keycloak

### Pelo console de administração

1. Abra `http://localhost:18080/auth/admin` e entre como `admin`. A senha está no Secret:
   ```bash
   kubectl -n platform get secret sso-secrets -o jsonpath='{.data.keycloak-admin-password}' | base64 -d; echo
   ```
2. No seletor de realm, no topo do menu lateral, troque de `master` para **tools** (aparece como "tools (local)").

| O que você quer ver                                           | Onde fica                                                                                                                                                                       |
|---------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| De onde vêm usuários e grupos                                 | **User federation → directory**: URL `ldaps://`, *Edit mode* `READ_ONLY`, *Cache policy* `MAX_LIFESPAN`. Na aba **Mappers**, `groups` mostra a OU de onde saem os grupos      |
| Lista de usuários                                             | **Users** (busque `alice` ou `*`). O usuário aparece depois do primeiro login ou de uma busca, que o importa do diretório                                                       |
| Dados da Alice, se está ativa                                 | **Users → alice → Details**. *Federation link* mostra `directory`; os campos vêm do diretório e ficam só leitura                                                                |
| Grupos da Alice                                               | **Users → alice → Groups**. Entrar ou sair de grupo é no AD (no lab, no OpenLDAP), não aqui: a federação é só leitura                                                          |
| Roles do Dave                                                 | **Users → dave → Role mapping**; desmarque *Hide inherited roles* para ver o `tools-gateway gateway-admin` que vem do grupo                                                      |
| Sessões abertas da Alice (e encerrar)                         | **Users → alice → Sessions** (menu ⋮ → *Sign out*)                                                                                                                              |
| Membros de um grupo                                           | **Groups → tools-httpbin → Members**                                                                                                                                            |
| Roles que um grupo dá                                         | **Groups → gateway-admins → Role mapping** (é daqui que o Dave herda `gateway-admin`)                                                                                           |
| Quem tem uma role direto                                      | **Clients → tools-gateway → Roles → gateway-admin → Users in role**. Mostra só atribuições diretas: a conta de serviço da `tools-cli` aparece, o Dave não, porque herda pelo grupo |
| Todas as sessões do realm                                     | **Sessions**                                                                                                                                                                    |
| Histórico de logins, logouts e refreshes                      | **Events → User events**                                                                                                                                                        |
| Quem mudou o quê no Keycloak                                  | **Events → Admin events**                                                                                                                                                       |
| **O token exatamente como o gateway vai receber**             | **Clients → tools-gateway → Client scopes → Evaluate**: escolha o usuário e abra *Generated access token*                                                                       |

A última linha é a melhor forma de entender por que uma rota aceitou ou negou alguém: o `groups` e o `resource_access.tools-gateway.roles` desse token são o que o gateway compara com o `anyOf`.

### Pelo terminal (kcadm)

```bash
export KUBECONFIG=$(k3d kubeconfig write dynamic-gateway-lab)
KC_ADMIN=$(kubectl -n platform get secret sso-secrets -o jsonpath='{.data.keycloak-admin-password}' | base64 -d)
kc() { kubectl -n platform exec deploy/keycloak -- /opt/keycloak/bin/kcadm.sh "$@" 2>/dev/null; }
kc config credentials --server http://localhost:8080/auth --realm master --user admin --password "$KC_ADMIN"

kc get users -r tools --fields username,email,enabled,federationLink        # usuários já importados
ALICE=$(kc get users -r tools -q username=alice -q exact=true --fields id | jq -r '.[0].id')
kc get users/$ALICE/groups -r tools --fields name                            # grupos da alice
kc get users/$ALICE/sessions -r tools | jq '.[] | {ipAddress, clients}'      # sessões abertas

GROUP=$(kc get groups -r tools -q search=tools-httpbin --fields id | jq -r '.[0].id')
kc get groups/$GROUP/members -r tools --fields username                      # membros do grupo

CLIENT=$(kc get clients -r tools -q clientId=tools-gateway --fields id | jq -r '.[0].id')
kc get clients/$CLIENT/evaluate-scopes/generate-example-access-token -r tools -q userId=$ALICE -q scope=openid \
  | jq '{preferred_username, groups, resource_access}'                      # o token que o gateway recebe
```

### No diretório do lab (OpenLDAP)

Grupos e membros mudam no diretório, como o IDM faria no AD:

```bash
LDAP_ADMIN=$(kubectl -n platform get secret sso-secrets -o jsonpath='{.data.ldap-admin-password}' | base64 -d)
ldap() { kubectl -n platform exec -i deploy/openldap -- "$@" -x -H ldap://localhost -D cn=admin,dc=tools,dc=local -w "$LDAP_ADMIN"; }

ldap ldapsearch -b ou=tools,dc=tools,dc=local '(objectClass=groupOfNames)' cn member   # grupos da aplicação e membros
```

---

## 4. Exemplo: só a Carol pode usar `/sso/token`

Hoje `/sso/token` exige `group:tools-httpbin`, e Alice e Carol estão nesse grupo, então as duas entram. Para restringir à Carol, a rota precisa exigir algo que **só a Carol tem**.

A rota não aceita regra por usuário (do tipo "só a carol"), e isso é proposital. Quem concede acesso é o IDM, colocando a pessoa num grupo do AD. Se a regra citasse pessoas, cada mudança de acesso viraria uma alteração de rota, fora do controle do IDM e sem trilha no AD.

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

É o modelo que o IDM descreveu: um grupo por acesso, na OU da aplicação, e quem entra nele é decidido fora do gateway. Com a função `ldap` da seção 3:

1. O IDM cria o grupo `tools-token` na OU `tools`, com a Carol:
   ```bash
   printf 'dn: cn=tools-token,ou=tools,dc=tools,dc=local\nobjectClass: groupOfNames\ncn: tools-token\nmember: uid=carol,ou=people,dc=tools,dc=local\n' \
     | ldap ldapadd
   ```
2. A rota passa a exigir esse grupo:
   ```bash
   kubectl -n platform patch gwr sso-token --type merge -p '{"spec":{"access":{"anyOf":["group:tools-token"]}}}'
   ```
3. A Carol "ganha" o grupo no próximo refresh, em até ~6 min, ou na hora com logout em `http://localhost:18080/_gateway/logout` e login de novo. Resultado: Alice dá 403 e Carol dá 200. O grupo aparece sozinho no Keycloak (**Groups**): a federação cria o grupo quando alguém dele faz login.
4. **Dar acesso à Alice sem mexer na rota:**
   ```bash
   printf 'dn: cn=tools-token,ou=tools,dc=tools,dc=local\nchangetype: modify\nadd: member\nmember: uid=alice,ou=people,dc=tools,dc=local\n' \
     | ldap ldapmodify
   ```
   Em até ~6 min ela passa a receber 200 sem logar de novo. É isso que o IDM faz no dia a dia, no AD.
5. A trilha da mudança fica no diretório (no AD, o log de auditoria dele). A auditoria do gateway mostra a partir de quando ela passou a acessar a rota.

### Voltar como estava

```bash
kubectl -n platform patch gwr sso-token --type merge -p '{"spec":{"access":{"anyOf":["group:tools-httpbin"]}}}'
ldap ldapdelete cn=tools-token,ou=tools,dc=tools,dc=local
```

---

## 5. Resumo de quem cuida de quê

| Mudança                           | Quem faz                  | Onde                                                                     | Quando vale                                    |
|-----------------------------------|---------------------------|--------------------------------------------------------------------------|------------------------------------------------|
| Qual grupo uma rota exige         | time da rota / plataforma | `access` da `GatewayRoute`                                               | segundos (v2) ou redeploy (v1)                 |
| Quem está em cada grupo           | IDM / suporte             | AD (no lab, OpenLDAP)                                                    | até ~6 min, no próximo refresh, sem novo login |
| Derrubar o acesso de alguém       | IDM                       | desabilitar a conta no AD, ou encerrar a sessão no Keycloak (na hora)    | até ~6 min                                     |
| Quem opera o gateway              | plataforma / IDM          | grupo `gateway-admins` no AD (role `gateway-admin` no Keycloak)          | até ~6 min                                     |
| Saber quem acessou o quê e quando | SOC                       | auditoria do gateway (`AUDIT`) + eventos do Keycloak, ligados pelo `sid` | sempre                                         |

---

## 6. Modelo de roles: operar o gateway não é usar as ferramentas

| Permissão                          | Para quê                                 | De onde vem                                                                         | Exemplo no lab           |
|------------------------------------|------------------------------------------|-------------------------------------------------------------------------------------|--------------------------|
| `group:<nome>` (negócio)           | usar uma ferramenta (ler, escrever)      | grupo do AD na OU da aplicação, federado pelo Keycloak                              | `tools-httpbin`, `tools-admins` |
| `role:gateway-admin` (operação)    | ler `GET /internal/routes`               | role do **client** `tools-gateway`, dada ao grupo `gateway-admins` do AD e à conta de serviço | Dave, `tools-cli`        |

As regras que o realm segue, e por quê:

- **Pessoa ganha acesso por grupo do AD.** Quem decide é o IDM, com trilha no AD. Nenhuma pessoa é cadastrada no Keycloak.
- **Role é do client, nunca do realm.** Role de realm vale para o realm inteiro: uma role `admin` criada para outra aplicação abriria rotas aqui. O gateway só lê `resource_access.tools-gateway.roles`, e os clients têm *Full scope allowed* desligado, então o token não carrega roles de outras aplicações.
- **Operação e negócio não se misturam.** `gateway-admin` só lê a tabela de rotas e não abre nenhuma ferramenta: o Dave e a `tools-cli` recebem 403 em `/sso/admin`. A Carol administra a ferramenta e recebe 403 em `/internal/routes`.
- **Conta de serviço não está no AD**, então recebe role do client direto (a `tools-cli` tem `gateway-admin` para automação de plataforma). Uma automação que precise usar uma ferramenta ganharia uma role de negócio própria, como `role:tools-kafka-admin` em `k8s/samples/borda/tools.yaml`.
- **Todo client que emite token para o gateway leva o mapper `groups`** (`tools-gateway` e `tools-cli`). Sem ele, quem chega por esse client fica sem grupos e recebe 403. Num Keycloak gerenciado, com mais clients, prefira um client scope `groups` default do realm.
- **Nome de grupo é único na OU da aplicação.** O claim `groups` leva só o nome (`full.path: false`), igual ao `group:<cn>` do provider `ldap`, para que trocar de provedor não mude nenhuma rota.

---

## 7. Onde o Keycloak guarda os dados

O Keycloak roda em modo produção, com banco. No banco ficam o realm, os clients, as roles, a cópia dos usuários importados do diretório (com o vínculo da federação), as sessões e os eventos. Senha e grupos continuam no AD. Reiniciar o pod do Keycloak não perde sessão nem configuração.

| Banco               | Como subir                                    | Peça                    | Console                     |
|---------------------|-----------------------------------------------|-------------------------|-----------------------------|
| PostgreSQL 17       | `make sso-up` (default)                       | `scripts/db/postgres.sh` | `make keycloak-db-shell`   |
| Oracle 26ai Free    | `KEYCLOAK_DB=oracle make sso-up`              | `scripts/db/oracle.sh`  | `KEYCLOAK_DB=oracle make keycloak-db-shell` |

No Oracle, o Keycloak usa o usuário `KEYCLOAK` do PDB `FREEPDB1`, só com permissão de criar objetos no próprio schema (`CREATE SESSION, TABLE, SEQUENCE, VIEW, PROCEDURE, TRIGGER, SYNONYM` e cota no tablespace `USERS`). O driver JDBC da Oracle não vem no Keycloak (licença), por isso esse caminho usa a imagem `keycloak-oracle:local`, montada de `docker/keycloak-oracle/Dockerfile` com o `ojdbc11` e o `orai18n` do Maven Central. Num Oracle da empresa, peça ao DBA um usuário com essas permissões e troque o `KC_DB_URL` (`jdbc:oracle:thin:@//<host>:1521/<service>`).

O realm é importado do `realm-tools.json` só quando ainda não existe no banco. Mudou o JSON, o diretório ou o banco, o `make sso-up` zera o banco e importa de novo. `make keycloak-reset` faz o mesmo na mão.

---

## 8. Keycloak com o AD de verdade (POC)

O realm do lab já vem pronto para o AD: a federação, os mappers e o grupo que recebe `gateway-admin` são os mesmos. Muda só o diretório, por variável:

```bash
DIRECTORY=ad \
LDAP_URL=ldaps://ad.corp.local:636 \
LDAP_BIND_DN='CN=svc-tools-gateway,OU=Services,DC=corp,DC=local' \
LDAP_BIND_PASSWORD='…' \
LDAP_USERS_DN='OU=Users,DC=corp,DC=local' \
LDAP_GROUPS_DN='OU=Tools,OU=Groups,DC=corp,DC=local' \
LDAP_CA_FILE=./ca-corporativa.pem \
HOST_PORT=18080 make sso-up
```

O `deploy-sso.sh` não sobe o OpenLDAP, grava a senha e a CA no Secret `keycloak-ldap` (sem passar a senha por linha de comando) e recria o Keycloak com o realm apontando para o AD:

| Configuração do realm                       | No AD                                                         | Por quê                                                                                         |
|---------------------------------------------|---------------------------------------------------------------|-------------------------------------------------------------------------------------------------|
| Conexão                                     | `ldaps://` com a CA em `KC_TRUSTSTORE_PATHS`                  | bind simples em `ldap://` manda a senha aberta; sem a CA, o Keycloak falha com *PKIX path building failed* |
| Conta do bind                               | conta de serviço só de leitura                                | o Keycloak nunca escreve no AD (*Edit mode* `READ_ONLY`)                                         |
| Usuário                                     | vendor `ad`, `sAMAccountName`, `objectGUID`                   | login com o usuário de rede; o id não muda quando a conta é renomeada ou movida de OU           |
| Senha                                       | bind no AD                                                    | a senha não fica no Keycloak; lockout e política de senha do AD continuam valendo               |
| Conta desabilitada, travada ou senha vencida | mapper *MSAD account controls*                                | o login é recusado, e uma sessão aberta cai no próximo refresh                                  |
| Grupos                                      | só da OU em `LDAP_GROUPS_DN`, estratégia `LOAD_GROUPS_BY_MEMBER_ATTRIBUTE_RECURSIVELY` | grupos aninhados, com o mesmo `1.2.840.113556.1.4.1941` do provider `ldap`; nada fora da OU vira permissão |
| Cache                                       | `MAX_LIFESPAN` de 1 min                                       | no cache padrão, o Keycloak guarda usuário e grupos sem prazo e uma remoção no AD não chega ao token |

Antes da POC, combine com o IDM:

- **O grupo de operação.** Crie `gateway-admins` na OU da aplicação, ou passe o nome do grupo que já existe em `GATEWAY_ADMINS_GROUP`.
- **As rotas.** Elas citam os grupos de verdade (`group:<cn do grupo>`). Os exemplos `routes-sso.yaml` usam os grupos do lab; troque por grupos da OU.
- **`LDAP_USERS_DN` numa OU de usuários**, não na raiz do domínio: na raiz o AD devolve *referrals* para DomainDnsZones e Configuration. Se os usuários estão espalhados, use o Global Catalog (`ldaps://ad.corp.local:3269`).
- **Atributos dos usuários.** Quem não tem `mail`, `givenName` ou `sn` no AD recebe a tela de completar o perfil no primeiro login.
- **Firewall.** O pod do Keycloak precisa alcançar o AD na 636 (ou 3269).

Para login transparente nas máquinas do domínio, ligue Kerberos/SPNEGO na mesma federação (*Allow Kerberos authentication*), com keytab do SPN `HTTP/<host do Keycloak>`. Se a empresa usa Entra ID (o Azure AD) em vez de AD local, não há LDAP: a federação é por OIDC/SAML (*Identity providers*), e os grupos chegam por um mapper do provedor.

Para conferir sem a POC: `make sso-test` roda no lab as mesmas verificações (usuário vindo do diretório, LDAPS, só leitura, cache de 1 min, OU, separação entre operar e usar).

## 9. Tela de login e "Entrar com Microsoft"

A tela de login do realm `tools` usa o tema `tools` (`k8s/sso/theme/tools`): o `keycloak.v2` com as cores e o cartão das páginas do gateway, em português, no claro e no escuro. O `keycloak.sh` publica o tema no ConfigMap `keycloak-theme-tools`, montado em `/opt/keycloak/themes/tools`. Mudar só o tema reinicia o Keycloak sem zerar o banco.

O botão **Entrar com Microsoft** é o provedor de identidade `microsoft` do realm, por OIDC, e não LDAP:

1. O usuário clica no botão e entra na Microsoft (Entra ID), com o MFA que a empresa exigir.
2. A Microsoft devolve o usuário ao Keycloak, que procura no AD, pela federação LDAP, quem tem o mesmo e-mail e associa as duas contas sem perguntar (fluxo `vincular-usuario-do-ad`).
3. A sessão é a desse usuário do AD: os grupos continuam vindo da OU da aplicação, e as rotas não mudam.

Quem existe na Microsoft mas não no AD é recusado: o Keycloak não cria usuário local. O token da Microsoft não chega ao gateway.

### No lab: a Microsoft simulada

Com `MICROSOFT=sim`, o default, o realm `microsoft-sim` faz o papel do Entra ID no mesmo Keycloak. Ele tem alice, bob, carol e dave, com os mesmos e-mails e a mesma senha do diretório, e a eve, que só existe "na Microsoft".

🌐 Abra `http://localhost:18080/sso/group/headers`, clique em **Entrar com Microsoft** e entre como alice na tela "Microsoft (simulado)". A volta é na rota, como alice, com `group:tools-httpbin`. Como eve, o Keycloak recusa o login.

### Com o Entra ID de verdade

1. No Entra ID, crie um **App Registration** (plataforma Web) com o redirect `http://localhost:18080/auth/realms/tools/broker/microsoft/endpoint` (fora do lab, a URL pública do Keycloak) e um client secret.
2. Em **Token configuration**, adicione a optional claim `email` ao ID token. Ela precisa ser igual ao `mail` do usuário no AD: é por ela que as contas se associam. Com o Entra Connect sincronizando o AD, já é.
3. Suba com as variáveis. O segredo vai para o Secret `keycloak-microsoft`, nunca para o repositório:

```bash
MICROSOFT=entra \
MICROSOFT_TENANT_ID=<tenant id> \
MICROSOFT_CLIENT_ID=<application (client) id> \
MICROSOFT_CLIENT_SECRET='…' \
HOST_PORT=18080 make lab linux
```

O realm simulado deixa de ser importado, e o provedor `microsoft` passa a apontar para `login.microsoftonline.com/<tenant>/v2.0`. Trocar de `sim` para `entra`, ou de tenant, reimporta o realm, e as sessões abertas caem. O Keycloak e o navegador precisam alcançar `login.microsoftonline.com`.
