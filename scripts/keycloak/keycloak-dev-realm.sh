#!/usr/bin/env bash
# Realm tools, o que o gateway espera, no Keycloak do keycloak-dev.sh, pela API de administração:
# o client scope httpbin-api, os clients tools-gateway e httpbin-api, a role gateway-admin, os
# grupos gateway-admins e ferramentas e os usuários alice e dave. Os usuários são locais, porque
# esse Keycloak não federa diretório. Com o port-forward do Keycloak aberto noutro terminal:
#
#   kubectl --context <contexto> -n <namespace> port-forward svc/keycloak 18080:8080
#   KUBE_CONTEXT=<contexto> NAMESPACE=<namespace> ALICE_PASSWORD=<senha> DAVE_PASSWORD=<senha> \
#       bash scripts/keycloak/keycloak-dev-realm.sh
#
# A senha do admin vem do Secret keycloak-admin. Rodar de novo não estraga nada: o que já existe
# dá 409 ou é pulado. No fim sai o segredo do client tools-gateway, que o gateway usa.
set -euo pipefail

if [[ -z "${KUBE_CONTEXT:-}" || -z "${NAMESPACE:-}" || -z "${ALICE_PASSWORD:-}" || -z "${DAVE_PASSWORD:-}" ]]; then
    echo "usage: KUBE_CONTEXT=<contexto> NAMESPACE=<namespace> ALICE_PASSWORD=<senha> DAVE_PASSWORD=<senha> $0" >&2
    exit 2
fi
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:18080/auth}"

json_string() {
    printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

admin_password="$(kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" get secret keycloak-admin \
    -o jsonpath='{.data.password}' | base64 -d)"
TOKEN="$(curl -s "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" -d client_id=admin-cli \
    -d grant_type=password -d username=admin --data-urlencode "password=${admin_password}" \
    | sed -E 's/.*"access_token":"([^"]+)".*/\1/' || true)"
if [[ "${TOKEN}" != ey* ]]; then
    echo "no admin token from ${KEYCLOAK_URL}: is the port-forward open?" >&2
    exit 1
fi

echo -n "realm tools: "
curl -s -o /dev/null -w '%{http_code}\n' -X POST "${KEYCLOAK_URL}/admin/realms" \
    -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' --data-binary @- <<'JSON'
{
  "realm": "tools",
  "displayName": "Tools",
  "enabled": true,
  "loginTheme": "tools",
  "internationalizationEnabled": true,
  "supportedLocales": ["pt-BR"],
  "defaultLocale": "pt-BR"
}
JSON

# Opcional em todo client novo do realm: o tools-gateway, criado depois, já o recebe.
SCOPE="$(curl -s -i -X POST "${KEYCLOAK_URL}/admin/realms/tools/client-scopes" \
    -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' --data-binary @- <<'JSON' \
    | tr -d '\r' | sed -n 's|^[Ll]ocation: .*/||p'
{
  "name": "httpbin-api",
  "protocol": "openid-connect",
  "attributes": {"include.in.token.scope": "true", "display.on.consent.screen": "false"},
  "protocolMappers": [{
    "name": "audience: httpbin-api",
    "protocol": "openid-connect",
    "protocolMapper": "oidc-audience-mapper",
    "config": {"included.client.audience": "httpbin-api", "access.token.claim": "true", "id.token.claim": "false", "introspection.token.claim": "true"}
  }]
}
JSON
)"
if [[ -n "${SCOPE}" ]]; then
    echo -n "client scope httpbin-api: "
    curl -s -o /dev/null -w '%{http_code}\n' -X PUT \
        "${KEYCLOAK_URL}/admin/realms/tools/default-optional-client-scopes/${SCOPE}" -H "Authorization: Bearer ${TOKEN}"
else
    echo "client scope httpbin-api: already there"
fi

# O partialImport acha os grupos pelo path: sem ele, falha com NullPointerException.
echo -n "clients, role, groups and users: "
curl -s -w '\n' -X POST "${KEYCLOAK_URL}/admin/realms/tools/partialImport" \
    -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' --data-binary @- <<JSON \
    | sed -E 's/.*("overwritten":[0-9]+,"added":[0-9]+,"skipped":[0-9]+).*/{\1}/'
{
  "ifResourceExists": "SKIP",
  "clients": [
    {"clientId": "httpbin-api", "enabled": true, "publicClient": false, "standardFlowEnabled": false, "directAccessGrantsEnabled": false},
    {
      "clientId": "tools-gateway",
      "enabled": true,
      "publicClient": false,
      "standardFlowEnabled": true,
      "directAccessGrantsEnabled": false,
      "redirectUris": ["http://localhost:8080/_gateway/login/oauth2/code/keycloak"],
      "attributes": {"pkce.code.challenge.method": "S256", "post.logout.redirect.uris": "http://localhost:8080/*", "standard.token.exchange.enabled": "true"},
      "protocolMappers": [
        {"name": "audience: tools-gateway", "protocol": "openid-connect", "protocolMapper": "oidc-audience-mapper",
         "config": {"included.client.audience": "tools-gateway", "access.token.claim": "true", "id.token.claim": "false", "introspection.token.claim": "true"}},
        {"name": "groups", "protocol": "openid-connect", "protocolMapper": "oidc-group-membership-mapper",
         "config": {"claim.name": "groups", "full.path": "false", "access.token.claim": "true", "id.token.claim": "false", "userinfo.token.claim": "false", "introspection.token.claim": "true"}}
      ]
    }
  ],
  "roles": {"client": {"tools-gateway": [{"name": "gateway-admin", "description": "Opera o gateway (GET /internal/routes)"}]}},
  "groups": [
    {"name": "gateway-admins", "path": "/gateway-admins", "clientRoles": {"tools-gateway": ["gateway-admin"]}},
    {"name": "ferramentas", "path": "/ferramentas"}
  ],
  "users": [
    {"username": "alice", "enabled": true, "email": "alice@exemplo.com", "emailVerified": true, "firstName": "Alice", "lastName": "Souza",
     "credentials": [{"type": "password", "value": "$(json_string "${ALICE_PASSWORD}")", "temporary": false}], "groups": ["/ferramentas"]},
    {"username": "dave", "enabled": true, "email": "dave@exemplo.com", "emailVerified": true, "firstName": "Dave", "lastName": "Lima",
     "credentials": [{"type": "password", "value": "$(json_string "${DAVE_PASSWORD}")", "temporary": false}], "groups": ["/gateway-admins"]}
  ]
}
JSON

gateway="$(curl -s -H "Authorization: Bearer ${TOKEN}" "${KEYCLOAK_URL}/admin/realms/tools/clients?clientId=tools-gateway" \
    | sed -E 's/^\[\{"id":"([^"]+)".*/\1/')"

# Fora do partialImport, que pula o client já existente. Sem o claim, o gateway não sabe por qual
# identity provider foi o login e recusa as rotas com access.identityProvider.
echo -n "mapper identity_provider: "
curl -s -o /dev/null -w '%{http_code}\n' -X POST \
    "${KEYCLOAK_URL}/admin/realms/tools/clients/${gateway}/protocol-mappers/models" \
    -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' --data-binary @- <<'JSON'
{
  "name": "identity_provider",
  "protocol": "openid-connect",
  "protocolMapper": "oidc-usersessionmodel-note-mapper",
  "config": {"user.session.note": "identity_provider", "claim.name": "identity_provider", "jsonType.label": "String",
             "access.token.claim": "true", "id.token.claim": "false", "introspection.token.claim": "true"}
}
JSON

echo -n "tools-gateway client secret: "
curl -s -H "Authorization: Bearer ${TOKEN}" "${KEYCLOAK_URL}/admin/realms/tools/clients/${gateway}/client-secret" \
    | sed -E 's/.*"value":"([^"]+)".*/\1/'
echo
