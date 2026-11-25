#!/usr/bin/env bash
# Keycloak e PostgreSQL num namespace que já existe em outro cluster, só com o realm
# master e sem diretório. Passam no Pod Security restricted, em que o keycloak.sh não
# sobe: o OpenLDAP do lab só roda como root.
#
#   KUBE_CONTEXT=<contexto> NAMESPACE=<namespace> scripts/keycloak/keycloak-dev.sh up
#   KUBE_CONTEXT=<contexto> NAMESPACE=<namespace> scripts/keycloak/keycloak-dev.sh down   # apaga também o banco
#   KUBE_CONTEXT=<contexto> NAMESPACE=<namespace> scripts/keycloak/keycloak-dev.sh probe  # que imagens o cluster baixa
#
# Os manifests vêm aqui dentro, e o script não usa o common.sh: copiado sozinho para
# outra máquina, ele roda igual. O namespace tem que existir; para um novo, o keycloak,
# há o scripts/keycloak/dev/namespace.yaml.
#
# O up gera os Secrets keycloak-postgres e keycloak-admin quando não existem, e o down
# não os apaga. Num banco que já tem admin, a senha do Secret keycloak-admin não vale.
#
# STORAGE_CLASS escolhe a StorageClass do volume do banco; sem ela, vale a default do
# cluster. Num cluster sem default, o PVC não sai de Pending, e nem o pod do PostgreSQL
# ("pod has unbound immediate PersistentVolumeClaims"). Para trocar a classe, rode o down
# antes: o StatefulSet não aceita mudar o volumeClaimTemplates depois de criado.
#
# EPHEMERAL_DB=1 põe o banco num emptyDir, sem volume, para o cluster sem StorageClass que
# se possa usar. O banco some quando o pod do PostgreSQL é recriado, e o Keycloak só refaz
# as tabelas e o admin ao reiniciar:
#   kubectl --context <contexto> -n <namespace> rollout restart deployment/keycloak
# Entre o volume e o emptyDir também vale o down antes.
#
# POSTGRES_IMAGE e KEYCLOAK_IMAGE trocam as imagens, para o cluster que só puxa de um registry
# interno, e IMAGE_PULL_SECRET dá aos pods o Secret de pull desse registry. O PostgreSQL roda
# como o usuário 70, o postgres da imagem alpine. O probe tenta baixar, num pod descartável,
# essas duas e as mesmas imagens oficiais noutros registries públicos (public.ecr.aws,
# mirror.gcr.io, Docker Hub), e diz quais o cluster alcança. Com REGISTRY=<host[:porta]>, ele
# testa, nesse registry interno, os caminhos comuns de um espelho do Docker Hub e do quay.io;
# no JFrog Artifactory, REGISTRY=<host>/<repositório> testa só esse repositório.
set -euo pipefail

POSTGRES_IMAGE="${POSTGRES_IMAGE:-postgres:17.5-alpine}"
KEYCLOAK_IMAGE="${KEYCLOAK_IMAGE:-quay.io/keycloak/keycloak:26.8.2}"

usage() {
    echo "usage: KUBE_CONTEXT=<contexto> NAMESPACE=<namespace> $0 up|down|probe" >&2
    exit 2
}

# Sem o contexto, o kubectl iria para o contexto atual da máquina.
[[ -n "${KUBE_CONTEXT:-}" && -n "${NAMESPACE:-}" ]] || usage

kube() {
    kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" "$@"
}

log() {
    echo "[keycloak-dev] $*"
}

# Banco num volume da STORAGE_CLASS ou da StorageClass default do cluster, ou num emptyDir
# (EPHEMERAL_DB=1). Em produção, um PostgreSQL gerenciado ou um operador (CloudNativePG),
# com backup.
postgres_manifest() {
    cat <<'YAML'
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: keycloak-postgres
  labels:
    app.kubernetes.io/name: keycloak-postgres
    app.kubernetes.io/part-of: dynamic-gateway-control-plane
spec:
  serviceName: keycloak-postgres
  replicas: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: keycloak-postgres
  template:
    metadata:
      labels:
        app.kubernetes.io/name: keycloak-postgres
        app.kubernetes.io/part-of: dynamic-gateway-control-plane
    spec:
      # 70 é o usuário postgres da imagem alpine. A imagem começa como root, e um
      # namespace com Pod Security restricted recusa o pod sem um usuário fixo.
      securityContext:
        runAsNonRoot: true
        runAsUser: 70
        runAsGroup: 70
        fsGroup: 70
        seccompProfile:
          type: RuntimeDefault
YAML
    containers_start postgres "${POSTGRES_IMAGE}"
    cat <<'YAML'
          imagePullPolicy: IfNotPresent
          securityContext:
            allowPrivilegeEscalation: false
            capabilities:
              drop: ["ALL"]
          ports:
            - name: postgres
              containerPort: 5432
          env:
            - name: TZ
              value: America/Sao_Paulo
            - name: POSTGRES_DB
              value: keycloak
            - name: POSTGRES_USER
              valueFrom:
                secretKeyRef: {name: keycloak-postgres, key: username}
            # Só vale quando o volume é criado: recriar o Secret depois não troca a senha no banco.
            - name: POSTGRES_PASSWORD
              valueFrom:
                secretKeyRef: {name: keycloak-postgres, key: password}
            # Subdiretório: a raiz do volume pode ter lost+found, e o initdb recusa diretório não vazio.
            - name: PGDATA
              value: /var/lib/postgresql/data/pgdata
          readinessProbe:
            exec:
              command: ["sh", "-c", "pg_isready -U \"$POSTGRES_USER\" -d keycloak"]
            initialDelaySeconds: 5
            periodSeconds: 5
          resources:
            requests:
              cpu: 100m
              memory: 256Mi
            limits:
              cpu: 1000m
              memory: 512Mi
          volumeMounts:
            - name: data
              mountPath: /var/lib/postgresql/data
YAML
    postgres_data
    cat <<'YAML'
---
apiVersion: v1
kind: Service
metadata:
  name: keycloak-postgres
  labels:
    app.kubernetes.io/name: keycloak-postgres
    app.kubernetes.io/part-of: dynamic-gateway-control-plane
spec:
  selector:
    app.kubernetes.io/name: keycloak-postgres
  ports:
    - name: postgres
      port: 5432
      targetPort: postgres
YAML
}

containers_start() {
    if [[ -n "${IMAGE_PULL_SECRET:-}" ]]; then
        echo "      imagePullSecrets:"
        echo "        - name: ${IMAGE_PULL_SECRET}"
    fi
    echo "      containers:"
    echo "        - name: $1"
    echo "          image: $2"
}

postgres_data() {
    if [[ "${EPHEMERAL_DB:-}" == "1" ]]; then
        cat <<'YAML'
      volumes:
        - name: data
          emptyDir:
            sizeLimit: 2Gi
YAML
        return
    fi
    cat <<'YAML'
  volumeClaimTemplates:
    - metadata:
        name: data
        labels:
          app.kubernetes.io/name: keycloak-postgres
      spec:
        accessModes: ["ReadWriteOnce"]
YAML
    if [[ -n "${STORAGE_CLASS:-}" ]]; then
        echo "        storageClassName: ${STORAGE_CLASS}"
    fi
    cat <<'YAML'
        resources:
          requests:
            storage: 5Gi
YAML
}

# Modo produção (`start`), com a versão e as features do Keycloak do lab (k8s/sso/keycloak.yaml).
# Só o realm master: o realm tools do lab depende do diretório e do login com Microsoft.
keycloak_manifest() {
    cat <<'YAML'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: keycloak
  labels:
    app.kubernetes.io/name: keycloak
    app.kubernetes.io/part-of: dynamic-gateway-control-plane
spec:
  replicas: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: keycloak
  template:
    metadata:
      labels:
        app.kubernetes.io/name: keycloak
        app.kubernetes.io/part-of: dynamic-gateway-control-plane
    spec:
      securityContext:
        runAsNonRoot: true
        seccompProfile:
          type: RuntimeDefault
YAML
    containers_start keycloak "${KEYCLOAK_IMAGE}"
    cat <<'YAML'
          imagePullPolicy: IfNotPresent
          # A troca V1 (token do IdP, para o OBO) pede token-exchange e o FGAP v1; a V2 (audience) já vem ligada.
          args: ["start", "--features=token-exchange,admin-fine-grained-authz:v1"]
          securityContext:
            allowPrivilegeEscalation: false
            capabilities:
              drop: ["ALL"]
          ports:
            - name: http
              containerPort: 8080
            - name: management
              containerPort: 9000
          env:
            - name: TZ
              value: America/Sao_Paulo
            # O profile keycloak do gateway procura o realm em <url pública>/auth/realms/<realm>.
            - name: KC_HTTP_RELATIVE_PATH
              value: /auth
            # Sem ele, a porta 9000 herda o /auth, e a saúde sai de /health.
            - name: KC_HTTP_MANAGEMENT_RELATIVE_PATH
              value: /
            # Sem hostname fixo, as URLs saem da requisição: responde no port-forward e atrás
            # do Ingress. Quando o Ingress existir, fixe KC_HOSTNAME na URL dele e ligue
            # KC_HOSTNAME_BACKCHANNEL_DYNAMIC, como no lab: com o hostname solto, o `iss` dos
            # tokens muda com o endereço por onde se chega, e o gateway recusa o token.
            - name: KC_HOSTNAME_STRICT
              value: "false"
            # O TLS termina no Ingress, que manda o esquema e o host originais nos X-Forwarded-*.
            - name: KC_HTTP_ENABLED
              value: "true"
            - name: KC_PROXY_HEADERS
              value: xforwarded
            - name: KC_HEALTH_ENABLED
              value: "true"
            # Eventos de login/logout/refresh no stdout: a outra metade da trilha de auditoria (o gateway loga o `sid`).
            - name: KC_SPI_EVENTS_LISTENER_JBOSS_LOGGING_SUCCESS_LEVEL
              value: info
            - name: KC_DB
              value: postgres
            - name: KC_DB_URL
              value: jdbc:postgresql://keycloak-postgres:5432/keycloak
            - name: KC_DB_USERNAME
              valueFrom:
                secretKeyRef: {name: keycloak-postgres, key: username}
            - name: KC_DB_PASSWORD
              valueFrom:
                secretKeyRef: {name: keycloak-postgres, key: password}
            # Criado só quando o banco ainda não tem admin: recriar o Secret depois não troca a senha.
            - name: KC_BOOTSTRAP_ADMIN_USERNAME
              value: admin
            - name: KC_BOOTSTRAP_ADMIN_PASSWORD
              valueFrom:
                secretKeyRef: {name: keycloak-admin, key: password}
          # Até 5 min: sem imagem otimizada, o `start` faz o build do Quarkus antes de subir.
          startupProbe:
            httpGet:
              path: /health/started
              port: management
            periodSeconds: 5
            failureThreshold: 60
          readinessProbe:
            httpGet:
              path: /health/ready
              port: management
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /health/live
              port: management
            periodSeconds: 10
          # Abaixo de ~2 GiB, o build do boot morre por OOM.
          resources:
            requests:
              cpu: 250m
              memory: 1Gi
            limits:
              cpu: 2000m
              memory: 2Gi
---
apiVersion: v1
kind: Service
metadata:
  name: keycloak
  labels:
    app.kubernetes.io/name: keycloak
    app.kubernetes.io/part-of: dynamic-gateway-control-plane
spec:
  selector:
    app.kubernetes.io/name: keycloak
  ports:
    - name: http
      port: 8080
      targetPort: http
YAML
}

ensure_secret() {
    local name="$1"
    shift
    kube get secret "${name}" >/dev/null 2>&1 && return
    log "Generating Secret ${name}"
    kube create secret generic "${name}" "$@" >/dev/null
}

# Pod recusado (Pod Security, quota) só aparece nos eventos: o rollout apenas estoura o tempo.
wait_ready() {
    kube rollout status "$1" --timeout="$2" && return
    kube get events --sort-by=.lastTimestamp | tail -20 >&2 || true
    exit 1
}

up() {
    ensure_secret keycloak-postgres --from-literal=username=keycloak --from-literal=password="$(openssl rand -hex 24)"
    ensure_secret keycloak-admin --from-literal=password="$(openssl rand -hex 16)"
    postgres_manifest | kube apply -f - >/dev/null
    wait_ready statefulset/keycloak-postgres 300s
    keycloak_manifest | kube apply -f - >/dev/null
    wait_ready deployment/keycloak 600s
    log "Console at http://localhost:18080/auth/admin (user admin) through"
    log "  kubectl --context ${KUBE_CONTEXT} -n ${NAMESPACE} port-forward svc/keycloak 18080:8080"
    log "Admin password:"
    log "  kubectl --context ${KUBE_CONTEXT} -n ${NAMESPACE} get secret keycloak-admin -o jsonpath='{.data.password}' | base64 -d"
}

down() {
    keycloak_manifest | kube delete --ignore-not-found -f -
    postgres_manifest | kube delete --ignore-not-found -f -
    # O volume do StatefulSet não sai junto com ele.
    kube delete pvc -l app.kubernetes.io/name=keycloak-postgres --ignore-not-found
    kube delete pod keycloak-dev-probe --ignore-not-found
}

# Nomes usuais do projeto ou repositório que espelha o Docker Hub ou o quay.io num Harbor,
# Nexus ou Artifactory; o vazio é o espelho na raiz do registry.
registry_images() {
    local prefix
    for prefix in "" library/ dockerhub/library/ docker-hub/library/ docker/library/ hub/library/ \
        proxy/library/ docker-proxy/library/ docker-remote/library/ dockerhub-remote/library/ \
        docker-hub-remote/library/ docker-virtual/library/ docker.io/library/; do
        echo "${REGISTRY}/${prefix}postgres:17.5-alpine"
    done
    for prefix in "" dockerhub/ docker-hub/ docker/ hub/ proxy/ docker-proxy/ docker-remote/ dockerhub-remote/ \
        docker-hub-remote/ docker-virtual/ docker.io/ quay/ quay-proxy/ quay-remote/ quay.io/; do
        echo "${REGISTRY}/${prefix}keycloak/keycloak:26.8.2"
    done
}

probe_images() {
    if [[ -n "${REGISTRY:-}" ]]; then
        registry_images
        return
    fi
    {
        echo "${POSTGRES_IMAGE}"
        echo public.ecr.aws/docker/library/postgres:17.5-alpine
        echo mirror.gcr.io/library/postgres:17.5-alpine
        echo "${KEYCLOAK_IMAGE}"
        echo mirror.gcr.io/keycloak/keycloak:26.8.2
        echo docker.io/keycloak/keycloak:26.8.2
    } | awk '!seen[$0]++'
}

# Cada container só roda o true: o que interessa é se a imagem desceu no nó.
probe_manifest() {
    cat <<'YAML'
apiVersion: v1
kind: Pod
metadata:
  name: keycloak-dev-probe
  labels:
    app.kubernetes.io/name: keycloak-dev-probe
    app.kubernetes.io/part-of: dynamic-gateway-control-plane
spec:
  restartPolicy: Never
  securityContext:
    runAsNonRoot: true
    runAsUser: 1000
    seccompProfile:
      type: RuntimeDefault
YAML
    if [[ -n "${IMAGE_PULL_SECRET:-}" ]]; then
        echo "  imagePullSecrets:"
        echo "    - name: ${IMAGE_PULL_SECRET}"
    fi
    echo "  containers:"
    probe_images | awk '{
        printf "    - name: image-%d\n      image: %s\n", NR - 1, $0
        print "      command: [\"true\"]"
        print "      securityContext:"
        print "        allowPrivilegeEscalation: false"
        print "        capabilities:"
        print "          drop: [\"ALL\"]"
        print "      resources:"
        print "        requests: {cpu: 10m, memory: 16Mi}"
        print "        limits: {cpu: 50m, memory: 32Mi}"
    }'
}

probe() {
    local uid states attempt pending pull_errors=""
    kube delete pod keycloak-dev-probe --ignore-not-found >/dev/null
    trap 'kube delete pod keycloak-dev-probe --ignore-not-found --wait=false >/dev/null 2>&1' EXIT
    probe_manifest | kube apply -f - >/dev/null
    uid="$(kube get pod keycloak-dev-probe -o jsonpath='{.metadata.uid}')"
    log "Pulling each image on one node of the cluster (up to 5 min)"
    for attempt in $(seq 1 100); do
        states="$(kube get pod keycloak-dev-probe -o jsonpath='{range .status.containerStatuses[*]}{.name}{"|"}{.state.waiting.reason}{"|"}{.state.terminated.reason}{.state.running.startedAt}{"|"}{.state.waiting.message}{"\n"}{end}' || true)"
        # Só o ErrImagePull traz o erro do registry: o ImagePullBackOff que vem depois diz apenas
        # "Back-off", e o Kubernetes junta os eventos parecidos num só.
        pull_errors="${pull_errors}
$(printf '%s\n' "${states}" | awk -F'|' '$2 == "ErrImagePull" && $4 != ""')"
        pending="$(printf '%s\n' "${states}" | awk -F'|' 'NF && $3 == "" && $2 !~ /^(ErrImagePull|ImagePullBackOff|InvalidImageName)$/' | wc -l)"
        if [[ -n "${states}" && "${pending}" -eq 0 ]]; then
            break
        fi
        sleep 3
    done
    if [[ -z "${states}" ]]; then
        kube get events --field-selector "involvedObject.uid=${uid}" >&2 || true
        exit 1
    fi
    PROBE_IMAGES="$(probe_images)" \
    PROBE_ERRORS="$(kube get events --field-selector "involvedObject.uid=${uid},reason=Failed" \
        -o jsonpath='{range .items[*]}{.message}{"\n"}{end}' 2>/dev/null || true)" \
    PROBE_PULL_ERRORS="${pull_errors}" PROBE_STATES="${states}" awk 'BEGIN {
        split(ENVIRON["PROBE_IMAGES"], image, "\n")
        errors = split(ENVIRON["PROBE_ERRORS"], error, "\n")
        lines = split(ENVIRON["PROBE_STATES"], state, "\n")
        seen = split(ENVIRON["PROBE_PULL_ERRORS"], pulled, "\n")
        for (s = 1; s <= seen; s++) {
            n = split(pulled[s], field, "|")
            if (n < 4 || (field[1] in pull_error)) continue
            message = field[4]
            for (f = 5; f <= n; f++) message = message "|" field[f]
            pull_error[field[1]] = message
        }
        for (l = 1; l <= lines; l++) {
            if (split(state[l], field, "|") < 3) continue
            name = image[substr(field[1], 7) + 1]
            if (field[3] != "") {
                print "  ok     " name
                if (name ~ /postgres/ && postgres == "") postgres = name
                if (name ~ /keycloak/ && keycloak == "") keycloak = name
                continue
            }
            reason = field[2] == "" ? "still pulling" : field[2]
            if ((field[1] in pull_error))
                reason = pull_error[field[1]]
            else
                for (e = 1; e <= errors; e++)
                    if (index(error[e], "\"" name "\"")) { reason = error[e]; break }
            sub(/^\(combined from similar events\): /, "", reason)
            if (reason ~ /401|[Uu]nauthorized|authorization failed|authentication required/) credentials = 1
            print "  FAIL   " name "\n         " substr(reason, 1, 220)
        }
        if (credentials)
            print "\nSome pulls asked for credentials: if the registry needs a login, add IMAGE_PULL_SECRET=<docker-registry Secret of the namespace>."
        if (postgres != "" && keycloak != "")
            print "\nUse: POSTGRES_IMAGE=" postgres " KEYCLOAK_IMAGE=" keycloak
        else if (ENVIRON["REGISTRY"] != "")
            print "\nNo usual mirror path of " (postgres == "" ? "PostgreSQL" : "Keycloak") " exists in " ENVIRON["REGISTRY"] \
                ": copy the image there (docker buildx imagetools create) or ask for a mirror of Docker Hub and quay.io."
        else
            print "\nNo copy of " (postgres == "" ? "PostgreSQL" : "Keycloak") " reached this cluster: it needs an internal" \
                " registry (REGISTRY=<host> probe tests the usual mirror paths in it)."
    }'
}

case "${1:-}" in
    up) up ;;
    down) down ;;
    probe) probe ;;
    *) usage ;;
esac
