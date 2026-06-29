# R31 — OpenShift Deployment Files Review & Prerequisites Audit

## Goal
Thoroughly review every file under `infra/k8s/` and produce a clean, corrected set of manifests
(or confirm they are superseded by the Helm charts in R32). Reconcile against the cluster's
already-installed operators and infrastructure.

## Cluster facts (confirmed)

| Fact | Value |
|---|---|
| **Operators installed** | RHBK (Keycloak), IBM MQ, OpenTelemetry, Tempo |
| **Namespace** | Helm value `{{ .Values.namespace }}` — created by user before `helm install` |
| **Storage class** | Default `thin-csi`, configurable via `{{ .Values.storageClass }}` |
| **Cluster domain** | Helm value `{{ .Values.clusterDomain }}` |
| **quay.io repos** | Public — no ImagePullSecret needed |

## Known issues that must be fixed as part of this review

| Ref | File | Problem |
|---|---|---|
| R02 | missing | `ServiceMeshMemberRoll` — sidecar injection inactive without it |
| R10 | `apps/spa-mobile-app/deployment.yaml` | Nginx runs as root — use `nginxinc/nginx-unprivileged` |
| R11 | `apps/payment-gateway/deployment.yaml` | `JWK_SET_URI` env var missing (JWT issuer vs JWK fetch split) |
| R12 | `apps/spa-mobile-app/route.yaml` | Hard-coded `<cluster-domain>` placeholder |
| R19 | `infra/k8s/infra/ibmmq/configmap-mqsc.yaml` | `MCAUSER('mqm')` — must be `MCAUSER('app')` |
| R20 | `infra/k8s/infra/keycloak/configmap-realm.yaml` | `directAccessGrantsEnabled` must be `true` |
| R21 | `apps/payment-gateway/deployment.yaml` | CORS origin hard-coded `localhost:3000` |
| R22 | `infra/k8s/infra/redpanda/` | Plain Deployment — must be StatefulSet + PVC |
| R26 | missing | `PeerAuthentication` MTLS STRICT missing |

## Additional audit items

### OTel / Tracing
- All apps export to `http://otel-collector:4317`. With the **OpenTelemetry Operator** installed,
  the collector's Kubernetes Service name depends on the `OpenTelemetryCollector` CR name.
  **Action:** find the CR name in the cluster and update the endpoint, or make it a Helm value.
- **Tempo Operator** is installed — identify the TempoStack CR and its OTLP distributor Service
  name. Update `OTEL_EXPORTER_OTLP_ENDPOINT` accordingly.

### Keycloak
- The existing plain Deployment in `infra/k8s/infra/keycloak/` will be **replaced** by RHBK
  Operator CRs in R32. Manifests in `infra/k8s/infra/keycloak/` can be archived or deleted.
- The Keycloak Route hostname must be deterministic so the SPA's OIDC `redirect_uri` is correct.
  It should be `keycloak.{{ .Values.clusterDomain }}`.
- The realm JSON import mechanism changed in Keycloak 20+: the env var `KEYCLOAK_IMPORT` no longer
  works with the new `quay.io/keycloak/keycloak` image. The RHBK Operator uses a `KeycloakRealm`
  CR (or `--import-realm` at startup). Confirm which approach the installed operator version uses.

### IBM MQ
- The existing StatefulSet in `infra/k8s/infra/ibmmq/` will be **replaced** by IBM MQ Operator
  `QueueManager` CR in R32.
- IBM MQ pods require UID 888. The `QueueManager` CR should handle the SCC requirement
  automatically when using the operator; confirm with cluster admin if `anyuid` SCC is needed.

### PostgreSQL
- Stays as a StatefulSet (no operator). Review `storageClassName` — parameterise as Helm value.
- Confirm init ConfigMap creates both `accounts_db` and `ledger_db` in one container lifecycle.

### Redpanda
- Plain Deployment in `infra/k8s/infra/redpanda/` will be **replaced** by official Redpanda Helm
  subchart in R32. The raw YAML can be archived.
- Topic creation Job (`job-create-topics.yaml`) will become a Helm post-install hook.

### Namespace
- `infra/k8s/infra/namespace.yaml` is **removed** from the chart — namespace created by user.
  Add a `NOTES.txt` in each chart reminding the user to pre-create the namespace.

### RBAC
- Check if any ServiceAccount or RoleBinding is needed for the OTel agent's metrics scraping or
  for Kafka consumer network policies inside the mesh.

### Resource limits
- Review each Deployment's `resources.requests/limits`. Ensure they are not rejected by any
  cluster-level LimitRange in the `showcase` namespace.

### Routes TLS
- All Routes are currently plain HTTP. Decide: edge TLS termination using the cluster's wildcard
  cert (simplest), or leave HTTP for the showcase.
- Keycloak **must** use HTTPS in production; for a showcase, HTTP edge termination is acceptable.

## Output
This review produces a list of diff-ready changes to existing YAML files, which are then folded
into the Helm chart templates in R32. Files that are superseded by operator CRs can be deleted.

## Acceptance
- Every finding in the "Known issues" table above is resolved.
- `infra/k8s/` raw YAML is either updated (for reference) or clearly marked as superseded by Helm.
- All audit items above are either fixed or explicitly deferred with a rationale.
