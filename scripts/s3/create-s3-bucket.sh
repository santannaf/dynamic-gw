#!/usr/bin/env bash
# Cria o bucket S3 usado pelo POC como backend de snapshot de rotas, com
# baseline de segurança (public access block) e versionamento opcional.
#
# Uso:
#   ./scripts/create-s3-bucket.sh
#   S3_REGION=sa-east-1 ./scripts/create-s3-bucket.sh
#   S3_BUCKET=meu-nome-custom ./scripts/create-s3-bucket.sh
#
# Variáveis (todas opcionais; se não setadas, derivam de aws sts):
#   S3_BUCKET   — nome do bucket (default: dynamic-gateway-routes-poc-<accountId>)
#   S3_REGION   — região AWS (default: us-east-1)
#   ENABLE_VERSIONING — "true" para habilitar versionamento (default: true)
#
# Requer: aws CLI configurado (AWS_ACCESS_KEY_ID/SECRET ou ~/.aws/credentials).
# O script é idempotente — pode rodar de novo num bucket existente.
set -euo pipefail

S3_REGION="${S3_REGION:-us-east-1}"
ENABLE_VERSIONING="${ENABLE_VERSIONING:-true}"

if ! command -v aws >/dev/null 2>&1; then
    echo "[create-s3-bucket] ERROR: aws CLI não encontrado no PATH." >&2
    exit 1
fi

echo "[create-s3-bucket] Verificando identidade AWS"
if ! aws sts get-caller-identity >/dev/null 2>&1; then
    cat >&2 <<'EOF'
[create-s3-bucket] ERROR: aws CLI sem credenciais válidas.

Configure ANTES de rodar o script de uma das formas:

  1) Env vars (IAM User direto):
       export AWS_ACCESS_KEY_ID=AKIA...
       export AWS_SECRET_ACCESS_KEY=...
       export AWS_DEFAULT_REGION=us-east-1

  2) AWS SSO:
       aws sso login --profile <seu-profile>
       export AWS_PROFILE=<seu-profile>

  3) Profile do ~/.aws/credentials:
       export AWS_PROFILE=<seu-profile>

Depois rode de novo: ./scripts/create-s3-bucket.sh
EOF
    exit 1
fi
ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
USER_ARN="$(aws sts get-caller-identity --query Arn --output text)"
echo "[create-s3-bucket]   Account: ${ACCOUNT_ID}"
echo "[create-s3-bucket]   Caller : ${USER_ARN}"

S3_BUCKET="${S3_BUCKET:-dynamic-gateway-routes-poc-${ACCOUNT_ID}}"
echo "[create-s3-bucket] Bucket alvo: ${S3_BUCKET} (região: ${S3_REGION})"

if aws s3api head-bucket --bucket "${S3_BUCKET}" 2>/dev/null; then
    echo "[create-s3-bucket] Bucket já existe — pulando create."
else
    echo "[create-s3-bucket] Criando bucket"
    if [ "${S3_REGION}" = "us-east-1" ]; then
        aws s3api create-bucket \
            --bucket "${S3_BUCKET}" \
            --region "${S3_REGION}"
    else
        aws s3api create-bucket \
            --bucket "${S3_BUCKET}" \
            --region "${S3_REGION}" \
            --create-bucket-configuration LocationConstraint="${S3_REGION}"
    fi
fi

echo "[create-s3-bucket] Bloqueando acesso público"
aws s3api put-public-access-block \
    --bucket "${S3_BUCKET}" \
    --public-access-block-configuration \
        "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true"

if [ "${ENABLE_VERSIONING}" = "true" ]; then
    echo "[create-s3-bucket] Habilitando versionamento"
    aws s3api put-bucket-versioning \
        --bucket "${S3_BUCKET}" \
        --versioning-configuration Status=Enabled
fi

POLICY_PATH="/tmp/${S3_BUCKET}-iam-policy.json"
cat > "${POLICY_PATH}" <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject"],
      "Resource": "arn:aws:s3:::${S3_BUCKET}/snapshots/*"
    }
  ]
}
EOF

cat <<EOF

[create-s3-bucket] Pronto.

  Bucket    : ${S3_BUCKET}
  Região    : ${S3_REGION}
  Versionado: ${ENABLE_VERSIONING}

Exporte as variáveis pro restante do fluxo (TESTE_S3.md):

  export S3_BUCKET=${S3_BUCKET}
  export S3_REGION=${S3_REGION}

Policy mínima pro IAM User do POC salva em:
  ${POLICY_PATH}

Anexe-a ao IAM User (console ou aws iam put-user-policy) antes de seguir.
EOF
