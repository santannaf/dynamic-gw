# dynamic-gateway-control-plane

A local proof-of-concept for a **Kubernetes-native dynamic gateway control plane**: platform users declare HTTP rotas via a `GatewayRoute` CRD, an operator consolidates them into a single snapshot, and a Spring Cloud Gateway instance reflects the change in seconds — **without restarting** the gateway pod, and **recovering all routes after restart** from the persisted snapshot.

The persistence backend in this POC is a Kubernetes `ConfigMap`. The architecture keeps the backend behind a thin abstraction so it can be swapped for S3 later without touching the operator's reconciler or the gateway's reload pipeline.

---

## Architecture

```
Platform user
   |
   v  kubectl apply GatewayRoute (CRD)
Kubernetes API
   |
   v  ADDED / MODIFIED / DELETED informer events
gateway-route-operator (Fabric8 informer + debounced reconciler)
   |
   v  RouteConfigPublisher.publish(snapshot)
ConfigMap  platform/gateway-routes  (data.routes.yaml = consolidated snapshot)
   |
   v  POST /internal/routes/reload   (best-effort signal)
dynamic-gateway (Spring Cloud Gateway, reactive)
   |
   v  RouteConfigProvider.load() -> RouteDefinitionMapper -> InMemoryRouteDefinitionRepository.replaceAll(...)
   |
   v  RefreshRoutesEvent
In-memory routes, ready to serve traffic
```

Visual diagram (Excalidraw): https://excalidraw.com/#json=6kDnSH7P21tm-Wb_Tt0zS,xCs6nkUE7mbTF8o4Tp2m1g

Three concerns are explicitly separated:

| Concern                     | Implementation                                       |
|-----------------------------|------------------------------------------------------|
| Declarative source of truth | `GatewayRoute` CRDs in the `platform` namespace      |
| Materialized state (today)  | YAML snapshot in `ConfigMap platform/gateway-routes` |
| Materialized state (future) | YAML snapshot in S3 — same model, plug-in interface  |
| Runtime state               | `RouteDefinition` map in the gateway pod's memory    |

The signal (`POST /internal/routes/reload`) is **best effort**. It is *not* the source of truth — the gateway's bootstrap runner reads the latest snapshot on pod startup, so a missed signal heals on the next pod restart or the next successful reconcile.

---

## Module layout

```
shared/    # storage-agnostic model: RouteConfigSnapshot, RouteConfigEntry, SnapshotCodec
gateway/   # Spring Cloud Gateway app + RouteConfigProvider (ConfigMap or S3 stub)
operator/  # Fabric8 informer + reconciler + RouteConfigPublisher (ConfigMap or S3 stub)
k8s/       # namespace, CRD, RBAC, Deployments, Service, Ingress, sample GatewayRoutes
scripts/   # create-cluster / delete-cluster / deploy-all / test-routes / restart-gateway-test
```

`shared` deliberately depends on **nothing** Kubernetes- or AWS-specific — that property is asserted by tests in both `gateway` and `operator` (`StorageAbstractionLeakTest`).

---

## Requirements

- JDK 25
- Docker
- `k3d` (for the local Kubernetes cluster)
- `kubectl`
- A free local TCP port `8080`

Optional: `make` (the `Makefile` is a thin convenience layer over the scripts).

---

## Getting started

```bash
# 1. Spin up the local cluster
./scripts/create-cluster.sh        # equivalent: make cluster

# 2. Build the jars, build the images, import them into the cluster, apply all manifests
./scripts/deploy-all.sh            # equivalent: make deploy

# 3. Apply a route
kubectl apply -f k8s/samples/route-httpbin.yaml

# 4. Hit it (no gateway restart involved)
curl http://localhost:8080/httpbin/get
```

`./scripts/test-routes.sh` runs the full apply → observe → patch → delete cycle and asserts the gateway reflects each change within a few seconds.

---

## Working with routes

### Create

```bash
kubectl apply -f k8s/samples/route-httpbin.yaml
kubectl get gatewayroutes -n platform
```

### Inspect what the gateway actually has in memory

```bash
curl http://localhost:8080/internal/routes | python3 -m json.tool
```

### Update

Edit the YAML or `kubectl patch`, then reapply. The operator coalesces a burst of events with a short debounce window (default 200ms).

```bash
kubectl patch gatewayroute httpbin-route -n platform \
    --type=merge -p '{"spec":{"stripPrefix":2}}'
```

### Delete

```bash
kubectl delete -f k8s/samples/route-httpbin.yaml
```

Within seconds, `GET /internal/routes` will no longer list `httpbin-route` and `curl /httpbin/get` will return 404.

### Disabled and invalid routes

- `k8s/samples/route-disabled.yaml` has `enabled: false`. The operator drops it from the published snapshot. The CRD still exists; nothing breaks if you re-enable it later.
- `k8s/samples/route-invalid.yaml` has a whitespace-only `targetUri`. The operator logs a validation warning and skips it; sibling routes are unaffected.

---

## Restart recovery (the second hard requirement)

```bash
kubectl apply -f k8s/samples/route-httpbin.yaml
curl http://localhost:8080/httpbin/get                              # works

kubectl rollout restart deployment/dynamic-gateway -n platform
kubectl rollout status  deployment/dynamic-gateway -n platform

curl http://localhost:8080/httpbin/get                              # still works
curl http://localhost:8080/internal/routes                          # lists httpbin-route
```

`./scripts/restart-gateway-test.sh` automates this and exits non-zero if recovery fails. The gateway recovers from the `ConfigMap`, not from any in-memory event — that's why it survives a pod restart even when the operator pod is also being recycled.

---

## Logs

```bash
kubectl logs -n platform deployment/dynamic-gateway        --tail=200 -f
kubectl logs -n platform deployment/gateway-route-operator --tail=200 -f
```

Key log lines to look for:

- Gateway: `Loading routes from route config store`, `Route config snapshot loaded version=…`, `Routes replaced in memory count=…`, `RefreshRoutesEvent published`, `Gateway bootstrap completed`.
- Operator: `Reconcile started id=…`, `Reconcile id=… listed N GatewayRoutes (V valid, D disabled, I invalid)`, `Reconcile id=… snapshot published to backend`, `Reconcile id=… reload signal sent`.

---

## Storage backend — ConfigMap today, S3 tomorrow

The persistence layer hides behind two interfaces in `shared`:

```java
// operator side
public interface RouteConfigPublisher {
    void publish(RouteConfigSnapshot snapshot);
}

// gateway side
public interface RouteConfigProvider {
    RouteConfigSnapshot load();
}
```

The active implementation is selected by a single Spring property per module:

```yaml
gateway:
  routes:
    store:
      type: configmap   # or s3

operator:
  routes:
    store:
      type: configmap   # or s3
```

Today both sides ship the `ConfigMap*` implementation as the default, plus an `S3*` stub that throws `UnsupportedOperationException("S3 route config store is not implemented in this POC")`. Adding real S3 means:

1. Implement `S3RouteConfigPublisher` (PutObject) and `S3RouteConfigProvider` (GetObject) against the shared `SnapshotCodec`.
2. Add the S3 SDK dependency.
3. Configure properties (`gateway.routes.store.type=s3` etc.) and the S3-specific properties.

**No change required** to: the CRD schema, the validator, the `SnapshotBuilder`, the reconciler, the gateway's `GatewayRouteReloadService`, the bootstrap runner, the `RouteDefinitionMapper`, the `InMemoryDynamicRouteDefinitionRepository`, or the `/internal/routes/reload` endpoint. `StorageAbstractionLeakTest` enforces this by asserting those classes do not import any backend-specific type.

---

## Known limitations (intentional in the POC)

This POC explicitly does **not** implement:

- Per-route auth, rate limiting, circuit breakers, retries, per-route timeouts, TLS.
- Multi-namespace or multi-gateway support.
- CRD `status.conditions` (`Accepted`, `Invalid`, `Published`, `FailedToReload`). Validation feedback is in operator logs only.
- Snapshot rollback, audit log, snapshot signing/checksums.
- A working S3 backend — only the stub seam.
- Target health checks.

The operator runs as a single replica. With more than one replica, last-writer-wins would apply.

---

## Next steps (post-POC)

1. CRD `status.conditions` so `kubectl describe gatewayroute` reflects validation and publish state.
2. Per-route timeout, retries, rate limit fields on the CRD and the snapshot.
3. Auth / scopes per route.
4. Real S3 storage backend + snapshot versioning.
5. Path-conflict validation across all routes during reconcile.
6. Replace the HTTP signal with a direct ConfigMap watch on the gateway side (or S3 polling) so the gateway can self-detect new snapshots.
7. Multi-gateway selection (label-based routing of CRDs to specific gateways).
8. Audit trail of snapshot changes (who, when, why).
9. Leader election for the operator so it can scale beyond one replica safely.

---

## License

Internal POC.
