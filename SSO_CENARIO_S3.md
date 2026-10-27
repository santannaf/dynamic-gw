# Cenário S3: rotas por `GatewayRoute`, snapshot num bucket S3, com SSO (Keycloak ou LDAP)

É o mesmo fluxo do [cenário CRD](SSO_CENARIO_CRD.md): os times declaram `GatewayRoute` com `access`, e o operator consolida. Só muda onde o snapshot fica: num objeto S3 (`s3://<bucket>/snapshots/routes.yaml`) em vez da ConfigMap. O SSO e a autorização por rota são idênticos, com os mesmos profiles de provedor. No lab, o S3 é um MinIO dentro do cluster, então não precisa de conta AWS. O caminho com bucket de verdade está mais abaixo.

Os outros cenários: [`SSO_CENARIO_CRD.md`](SSO_CENARIO_CRD.md) e [`SSO_CENARIO_PROPERTIES.md`](SSO_CENARIO_PROPERTIES.md). O modelo de SSO e autorização está em [`SSO_E_AUTORIZACAO.md`](SSO_E_AUTORIZACAO.md). O S3 sem SSO, com AWS e IAM, está em [`TESTE_S3.md`](TESTE_S3.md).

## Como funciona

```
time ── kubectl apply ──► GatewayRoute (spec.access)
                                │  operator valida, consolida e faz PutObject
                                ▼
                 s3://<bucket>/snapshots/routes.yaml   (MinIO no lab, S3 na AWS)
                                │  1) operator: POST /internal/routes/reload no Service → a réplica que recebe relê na hora
                                │  2) cada réplica: HEAD a cada 15 s, relê quando o ETag muda
                                ▼
browser / API ──► nginx-lb ──► gateway (profiles s3 + keycloak | ldap) ──► backend
                                │
                                ▼
                     Keycloak (OIDC)  ou  diretório (LDAP/AD)
```

Depois de gravar, o operator manda o sinal de reload pelo Service do gateway (publish-then-signal). A réplica que recebe o sinal relê o bucket na hora; as outras descobrem a mudança pelo ETag, em até `poll-interval` (15 s).

## O que já está configurado

| Peça | Arquivo | O que tem |
|---|---|---|
| Profile do store, embarcado | `gateway/src/main/resources/application-s3.yaml` | `type: s3`, key `snapshots/routes.yaml`, `poll-interval: 15s`, região `us-east-1` |
| Profile do provedor, embarcado | `application-keycloak.yaml` ou `application-ldap.yaml` | os mesmos do cenário CRD |
| Deployment do gateway | `k8s/sso/gateway-s3-keycloak.yaml` ou `k8s/sso/gateway-s3-ldap.yaml` | igual ao do cenário CRD (2 réplicas); muda só o store |
| Deployment do operator | `k8s/sso/operator-s3.yaml` | publica no bucket em vez da ConfigMap |
| S3 local | `k8s/s3/minio.yaml` | MinIO com PVC e o bucket criado no startup |
| Rotas de exemplo | `k8s/samples/routes-sso.yaml` | as mesmas 7 rotas `/sso/*` |

**Profiles ativos:** `SPRING_PROFILES_ACTIVE=s3,keycloak` ou `SPRING_PROFILES_ACTIVE=s3,ldap`.

### Propriedades

O store (`gateway.routes.store.…` no gateway, `operator.routes.store.…` no operator):

| Propriedade | Profile | MinIO (lab) | AWS |
|---|---|---|---|
| `type` | `s3` | | |
| `s3.bucket` | vazio, vem do ambiente | `dynamic-gateway-routes` | o seu bucket (`S3_BUCKET`) |
| `s3.key` | `snapshots/routes.yaml` | | |
| `s3.region` | `us-east-1` | | `S3_REGION` |
| `s3.endpoint` | vazio (AWS) | `http://minio.platform.svc.cluster.local:9000` | vazio |
| `s3.path-style-access` | `false` | `true` | `false` |
| `s3.poll-interval` (só o gateway) | `15s` | | |
| credenciais (`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`) | `envFrom` de um Secret | `minio-credentials` (gerado) | `aws-credentials` (você cria) |

A segurança (`gateway.security.…` e `gateway.audit.…`) é a mesma do cenário CRD:

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

O manifest do gateway passa o bucket, a região, o endpoint, o path-style, `SPRING_PROFILES_ACTIVE`, `GATEWAY_SECURITY_PUBLIC_BASE_URL` e os segredos (`SESSION_KEYS_0_*`, mais `OIDC_CLIENT_SECRET` ou `LDAP_BIND_PASSWORD`), como na tabela do [cenário CRD](SSO_CENARIO_CRD.md#propriedades).

## Subir

Pré-requisito: cluster e plataforma v2 no ar (`make cluster` e `make deploy`; ver [`TESTE_SSO.md`](TESTE_SSO.md), seção 0). O cenário S3 troca o operator e o gateway que o `make deploy` criou.

```bash
export HOST_PORT=18080 CLUSTER_NAME=dynamic-gateway-lab BASE=http://localhost:18080
export KUBECONFIG=$(k3d kubeconfig write $CLUSTER_NAME)

make s3-sso-up                   # MinIO + operator no S3 + gateway com s3,keycloak
PROVIDER=ldap make s3-sso-up     # MinIO + operator no S3 + gateway com s3,ldap
```

O script sobe o MinIO e gera o Secret `minio-credentials`. Aplica o operator e **espera o primeiro snapshot chegar ao bucket** antes de trocar o gateway. Sem essa espera, o gateway subiria lendo "objeto não existe" e ficaria com 0 rotas até o próximo poll.

### Com um bucket de verdade na AWS

Com `AWS_ACCESS_KEY_ID` e `AWS_SECRET_ACCESS_KEY` exportados no shell, como no [`TESTE_S3.md`](TESTE_S3.md):

```bash
./scripts/create-s3-bucket.sh        # cria o bucket e imprime os export S3_BUCKET/S3_REGION
export S3_BUCKET=<bucket> S3_REGION=us-east-1
kubectl -n platform create secret generic aws-credentials \
  --from-literal=AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY_ID \
  --from-literal=AWS_SECRET_ACCESS_KEY=$AWS_SECRET_ACCESS_KEY
S3_BACKEND=aws make s3-sso-up
```

O caminho com AWS não foi exercitado no lab, porque não havia conta. O código e os manifests são os mesmos do MinIO; mudam o endpoint (vazio), o path-style (`false`) e o Secret.

## Testar

```bash
make s3-sso-test                 # passed=33 failed=0
PROVIDER=ldap make s3-sso-test   # passed=22 failed=0
```

São os mesmos e2e do cenário CRD, com uma checagem a mais: o log do gateway prova que as rotas vieram de `s3://`. À mão:

```bash
kubectl -n platform logs -l app.kubernetes.io/name=dynamic-gateway --tail=-1 | grep -E 'profiles are active|S3 route watcher|Loaded route config snapshot from s3'

# o snapshot dentro do MinIO, com o access de cada rota
kubectl -n platform exec deploy/minio -- bash -c \
  'mc alias set lab http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && mc cat lab/dynamic-gateway-routes/snapshots/routes.yaml' \
  | grep -A14 'id: "sso-token"'
```

🌐 No browser é igual ao cenário CRD: `http://localhost:18080/sso/group/headers` como `alice` (200) e como `bob` (403).

## Mudar quem acessa uma rota

Igual ao cenário CRD, sempre pelo `GatewayRoute`:

```bash
kubectl -n platform patch gwr sso-group --type merge -p '{"spec":{"access":{"anyOf":["group:tools-admins"]}}}'
```

Vale na hora na réplica que recebe o sinal do operator e em até 15 s nas demais. Para encurtar a cauda: `GATEWAY_ROUTES_STORE_S3_POLL_INTERVAL=5s` no Deployment do gateway, ao custo de mais `HEAD` no bucket (um por réplica a cada intervalo). Não edite o objeto no bucket à mão: o operator sobrescreve no próximo reconcile.

## Em outro ambiente

O SSO se sobrescreve com as mesmas variáveis do [cenário CRD](SSO_CENARIO_CRD.md#em-outro-ambiente) (Keycloak ou AD). Do S3:

```bash
GATEWAY_ROUTES_STORE_S3_BUCKET=<bucket>      OPERATOR_ROUTES_STORE_S3_BUCKET=<bucket>
GATEWAY_ROUTES_STORE_S3_REGION=sa-east-1     OPERATOR_ROUTES_STORE_S3_REGION=sa-east-1
# endpoint e path-style só para S3 compatível (MinIO, Ceph...)
```

## Cuidados

- **Quem escreve no bucket decide quem acessa o quê**, porque o snapshot carrega o `access` de cada rota. Use o mínimo de permissão: o operator com `s3:PutObject` naquela key, e o gateway com `s3:GetObject`, que também cobre o `HEAD`. No lab, os dois usam a mesma credencial root do MinIO.
- **Credenciais só por chave estática** (`AWS_ACCESS_KEY_ID` e `AWS_SECRET_ACCESS_KEY` num Secret). IRSA (web identity) precisaria do módulo `software.amazon.awssdk:sts`, que não está no build.
- **Se o objeto some ou ainda não existe, o gateway fica com 0 rotas** (WARN `Object s3://… not found`) até o operator publicar de novo. O PVC do MinIO evita isso quando o pod reinicia.
- **O MinIO é só para o lab:** imagem `bitnamilegacy/minio`, sem TLS, credencial root.
- **Imagem antiga não sobe com o store S3.** Antes desta mudança, o AOT congelava o `@ConditionalOnProperty` do `S3Client` no build e o gateway nativo falhava no startup com `type=s3`. Se aparecer `required a bean of type 'S3Client'`, rode `make load-images`.
- **Os cuidados do cenário CRD valem aqui:** sem o profile do provedor, o `access` é ignorado; no LDAP, `role:` nunca casa.

## Voltar

```bash
make s3-sso-down     # gateway e operator voltam para a ConfigMap sem SSO; remove as rotas /sso/*, o MinIO (com o PVC), Keycloak e OpenLDAP
make sso-up          # ou: vai direto para o cenário CRD com Keycloak (o MinIO fica no ar, sem uso)
```
