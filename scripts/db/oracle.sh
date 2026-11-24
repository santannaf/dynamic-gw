#!/usr/bin/env bash
# Banco do Keycloak no lab com Oracle (k8s/sso/keycloak-oracle.yaml): Oracle AI
# Database 26ai Free, a mesma imagem oficial do env-oracle, na variante lite.
#
#   KEYCLOAK_DB=oracle HOST_PORT=18080 make sso-up   # o Keycloak usa este banco no lugar do PostgreSQL
#
#   scripts/db/oracle.sh up      # Secret keycloak-oracle (uma vez), StatefulSet com volume, usuário KEYCLOAK no FREEPDB1
#   scripts/db/oracle.sh reset   # recria o usuário KEYCLOAK (apaga as tabelas); o Keycloak reimporta o realm no próximo boot
#   scripts/db/oracle.sh empty   # sai com 0 se o Keycloak ainda não criou as tabelas
#   scripts/db/oracle.sh shell   # sqlplus como sysdba (alter session set container = FREEPDB1;)
#   scripts/db/oracle.sh down    # remove o banco e o volume (o Secret keycloak-oracle fica)
#
# ORACLE_IMAGE troca a imagem, por exemplo pela completa:
#   ORACLE_IMAGE=container-registry.oracle.com/database/free:latest
# O reset é para o Keycloak parado: use scripts/keycloak/keycloak.sh reset.
set -euo pipefail
LOG_PREFIX=oracle
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"

ORACLE_IMAGE="${ORACLE_IMAGE:-container-registry.oracle.com/database/free:23.26.0.0-lite}"
PDB=FREEPDB1

# SQL pelo stdin, como sysdba: nenhuma senha vai em argumento de processo.
sysdba() {
    kubectl -n "${NAMESPACE}" exec -i statefulset/keycloak-oracle -- sqlplus -s / as sysdba
}

# Usuário do Keycloak no PDB (não no CDB), com tablespace próprio (a imagem lite
# não tem USERS, e o default do PDB é o SYSTEM). Só cria objetos no próprio schema.
ensure_user() {
    local password
    password="$(kubectl -n "${NAMESPACE}" get secret keycloak-oracle -o jsonpath='{.data.password}' | base64 -d)"
    sysdba >/dev/null <<SQL
WHENEVER SQLERROR EXIT SQL.SQLCODE
SET FEEDBACK OFF
ALTER SESSION SET CONTAINER = ${PDB};
DECLARE
  n   INTEGER;
  dir VARCHAR2(512);
BEGIN
  SELECT COUNT(*) INTO n FROM dba_tablespaces WHERE tablespace_name = 'KEYCLOAK';
  IF n = 0 THEN
    -- Mesmo diretório dos datafiles do PDB, dentro do volume.
    SELECT SUBSTR(file_name, 1, INSTR(file_name, '/', -1)) INTO dir
      FROM dba_data_files WHERE tablespace_name = 'SYSTEM';
    EXECUTE IMMEDIATE 'CREATE TABLESPACE keycloak DATAFILE ''' || dir
      || 'keycloak01.dbf'' SIZE 100M AUTOEXTEND ON NEXT 50M MAXSIZE 4G';
  END IF;
  SELECT COUNT(*) INTO n FROM dba_users WHERE username = 'KEYCLOAK';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'CREATE USER keycloak IDENTIFIED BY "${password}" DEFAULT TABLESPACE keycloak QUOTA UNLIMITED ON keycloak';
  END IF;
END;
/
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE VIEW, CREATE PROCEDURE, CREATE TRIGGER, CREATE SYNONYM TO keycloak;
-- O Liquibase do Keycloak consulta a lixeira ao ler o schema; sem isto, avisa a cada migração.
GRANT SELECT ON SYS.DBA_RECYCLEBIN TO keycloak;
EXIT;
SQL
}

up() {
    ensure_image "${ORACLE_IMAGE}"
    if ! kubectl -n "${NAMESPACE}" get secret keycloak-oracle >/dev/null 2>&1; then
        log "Generating Secret keycloak-oracle"
        # Senhas com maiúscula, minúscula e dígito, até 30 caracteres (limite do Oracle).
        kubectl -n "${NAMESPACE}" create secret generic keycloak-oracle \
            --from-literal=username=keycloak \
            --from-literal=password="Kc$(openssl rand -hex 12)9" \
            --from-literal=admin-password="Ora$(openssl rand -hex 12)9" >/dev/null
    fi
    render k8s/sso/keycloak-oracle.yaml | kubectl apply -f - >/dev/null
    log "Waiting for Oracle (the first boot creates the database and takes a few minutes)"
    wait_rollout statefulset/keycloak-oracle 1200s
    ensure_user
    publish_keycloak_db oracle "jdbc:oracle:thin:@//keycloak-oracle.${NAMESPACE}.svc.cluster.local:1521/${PDB}" \
        keycloak-oracle
}

reset() {
    log "Recreating user KEYCLOAK in ${PDB} (drops every Keycloak table)"
    sysdba >/dev/null <<SQL
WHENEVER SQLERROR EXIT SQL.SQLCODE
ALTER SESSION SET CONTAINER = ${PDB};
BEGIN
  EXECUTE IMMEDIATE 'DROP USER keycloak CASCADE';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -1918 THEN RAISE; END IF;
END;
/
EXIT;
SQL
    ensure_user
}

empty() {
    local count
    count="$(sysdba <<SQL | tr -d '[:space:]'
SET HEADING OFF FEEDBACK OFF PAGESIZE 0
ALTER SESSION SET CONTAINER = ${PDB};
SELECT COUNT(*) FROM dba_tables WHERE owner = 'KEYCLOAK' AND table_name = 'REALM';
EXIT;
SQL
)"
    [[ "${count}" == "0" ]]
}

down() {
    render k8s/sso/keycloak-oracle.yaml | kubectl delete --ignore-not-found -f -
    # O volume do StatefulSet não sai junto com ele.
    kubectl -n "${NAMESPACE}" delete pvc -l app.kubernetes.io/name=keycloak-oracle --ignore-not-found
}

case "${1:-}" in
    up) up ;;
    reset) reset ;;
    empty) empty ;;
    shell) kubectl -n "${NAMESPACE}" exec -it statefulset/keycloak-oracle -- sqlplus / as sysdba ;;
    down) down ;;
    *) echo "usage: $0 up|reset|empty|shell|down" >&2; exit 2 ;;
esac
