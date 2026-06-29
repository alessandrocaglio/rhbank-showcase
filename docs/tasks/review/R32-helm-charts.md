# R32 — Helm Charts for Infrastructure and Applications

## Goal
Replace the raw `infra/k8s/` YAML with two Helm charts that can deploy the full showcase to any
OpenShift cluster with a single `helm install` per chart. All cluster-specific values are
externalised into `values.yaml`.

## Cluster facts (confirmed)

| Fact | Value |
|---|---|
| **Operators installed** | RHBK (Keycloak), IBM MQ, OpenTelemetry, Tempo — use CRs, not plain Deployments |
| **Namespace** | Pre-created by user; referenced as `{{ .Values.namespace }}` |
| **Storage class** | Default `thin-csi`; override via `storageClass` value |
| **Cluster domain** | Required; set via `clusterDomain` value |
| **quay.io repos** | Public — no ImagePullSecret |
| **Redpanda** | Official Redpanda Helm subchart |

---

## Chart layout

```
infra/helm/
├── showcase-infra/          # Chart 1 — infrastructure
│   ├── Chart.yaml
│   ├── values.yaml
│   └── templates/
│       ├── NOTES.txt
│       ├── postgres/
│       │   ├── statefulset.yaml
│       │   ├── service.yaml
│       │   ├── pvc.yaml
│       │   ├── secret.yaml
│       │   └── configmap-init.yaml
│       ├── keycloak/
│       │   ├── keycloak-cr.yaml          # Keycloak CR (RHBK Operator)
│       │   ├── keycloakrealm-cr.yaml     # KeycloakRealm CR
│       │   ├── configmap-realm.yaml      # realm JSON mounted by the CR
│       │   └── route.yaml
│       └── ibmmq/
│           ├── queuemanager-cr.yaml      # QueueManager CR (IBM MQ Operator)
│           └── configmap-mqsc.yaml       # MQSC config referenced by CR
│
└── showcase-apps/           # Chart 2 — application microservices
    ├── Chart.yaml            # depends on: redpanda/redpanda (subchart)
    ├── values.yaml
    └── templates/
        ├── NOTES.txt
        ├── _helpers.tpl
        ├── servicemesh/
        │   ├── servicemeshmemberroll.yaml
        │   └── peerauthentication.yaml
        ├── redpanda/
        │   └── topics-job.yaml           # post-install hook: create Kafka topics
        ├── account-verifier/
        │   ├── deployment.yaml
        │   └── service.yaml
        ├── payment-gateway/
        │   ├── deployment.yaml
        │   ├── service.yaml
        │   └── route.yaml
        ├── transaction-engine/
        │   ├── deployment.yaml
        │   └── service.yaml
        ├── clearing-house/
        │   ├── deployment.yaml
        │   └── service.yaml
        └── spa-mobile-app/
            ├── deployment.yaml
            ├── service.yaml
            └── route.yaml
```

---

## Chart 1 — showcase-infra

### values.yaml (key fields)
```yaml
namespace: showcase
storageClass: thin-csi
clusterDomain: ""   # required — e.g. apps.mycluster.example.com

postgres:
  storageSize: 2Gi
  password: apppassword   # override in production

keycloak:
  # RHBK Operator CR fields
  instances: 1
  adminPassword: admin   # override in production
  # Route host: keycloak.{{ .Values.clusterDomain }}

ibmmq:
  # IBM MQ Operator QueueManager CR fields
  image: icr.io/ibm-messaging/mq:9.3.5.1-r2
  storageClass: thin-csi
  storageSize: 2Gi
  queueManager: QM1
  channel: DEV.APP.SVRCONN
  queue: DEV.QUEUE.CLEARING
```

### Keycloak — RHBK Operator CRs
- Emit a `Keycloak` CR (v1alpha1 or v2alpha1, match installed operator version).
- Emit a `KeycloakRealm` CR that references the realm JSON ConfigMap.
- The realm JSON ConfigMap is the same `payment-realm.json` content as today, but the import
  mechanism shifts from env var to `KeycloakRealm` CR.
- Route for Keycloak is created by the operator or by a separate `Route` template — confirm
  whether the RHBK Operator creates the Route automatically.

### IBM MQ — IBM MQ Operator CR
- Emit a `QueueManager` CR with:
  - `spec.license.accept: true` and the correct license ID for the community image.
  - MQSC config via a ConfigMap referenced in the CR (`spec.queueManager.mqsc`).
  - `MCAUSER('app')` in the MQSC config (R19 fix).
  - Storage class and size from values.
- The operator manages the StatefulSet, Service, and route internally.

### PostgreSQL — plain StatefulSet
- No operator. Template from existing `infra/k8s/infra/postgres/`.
- `storageClassName: {{ .Values.storageClass }}` in PVC.
- Init ConfigMap creates both `accounts_db` and `ledger_db`.

---

## Chart 2 — showcase-apps

### Chart.yaml dependencies
```yaml
dependencies:
  - name: redpanda
    version: "5.x.x"        # pin to a stable release
    repository: https://charts.redpanda.com
    condition: redpanda.enabled
```

Run `helm dependency update infra/helm/showcase-apps` before install.

### values.yaml (key fields)
```yaml
namespace: showcase
clusterDomain: ""     # required

images:
  registry: quay.io/acaglio
  tag: latest
  pullPolicy: Always

otel:
  collectorEndpoint: http://otel-collector:4317   # override with actual Service name in cluster

keycloak:
  internalUrl: ""    # e.g. http://keycloak.showcase.svc.cluster.local:8080 — JWK fetch
  externalUrl: ""    # e.g. https://keycloak.apps.mycluster.example.com — JWT issuer claim

cors:
  allowedOrigins: ""  # defaults to https://spa-mobile-app.{{ .Values.clusterDomain }}

serviceMesh:
  enabled: true
  memberRollName: default   # name of ServiceMeshControlPlane to join

redpanda:
  enabled: true
  # Redpanda subchart values — override as needed

storageClass: thin-csi

resources:
  accountVerifier:   { requests: { memory: 256Mi, cpu: 100m }, limits: { memory: 512Mi, cpu: 500m } }
  paymentGateway:    { requests: { memory: 256Mi, cpu: 100m }, limits: { memory: 512Mi, cpu: 500m } }
  transactionEngine: { requests: { memory: 256Mi, cpu: 100m }, limits: { memory: 512Mi, cpu: 500m } }
  clearingHouse:     { requests: { memory: 256Mi, cpu: 100m }, limits: { memory: 512Mi, cpu: 500m } }
  spaMobileApp:      { requests: { memory: 64Mi,  cpu: 50m  }, limits: { memory: 128Mi, cpu: 200m } }
```

### _helpers.tpl
- `showcase.labels` — standard K8s labels (`app.kubernetes.io/name`, `app.kubernetes.io/version`, `helm.sh/chart`)
- `showcase.otelEnv` — named template emitting the 6 OTel env vars (service name as argument); used in every Java Deployment
- `showcase.keycloakExternalUrl` — defaults to `https://keycloak.{{ .Values.clusterDomain }}` if `keycloak.externalUrl` is empty
- `showcase.corsOrigin` — defaults to `https://spa-mobile-app.{{ .Values.clusterDomain }}`

### Service Mesh templates
- `ServiceMeshMemberRoll` — adds `{{ .Values.namespace }}` to the mesh (R02 fix), rendered only when `serviceMesh.enabled=true`
- `PeerAuthentication` with `mode: STRICT` for the namespace (R26 fix)

### Redpanda subchart
- Configured for single-node dev mode (1 broker, no TLS, no SASL).
- Topics created by a post-install `Job` (`helm.sh/hook: post-install,post-upgrade`).
- Redpanda StorageClass set via `redpanda.storage.persistentVolume.storageClass`.

### SPA nginx
- Use `nginxinc/nginx-unprivileged` (R10 fix) — runs as non-root UID 101.
- Route host: `spa-mobile-app.{{ .Values.clusterDomain }}`.

### payment-gateway JWT fix (R11)
- Two env vars:
  - `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` ← `keycloak.externalUrl` (JWT claim check)
  - `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` ← `keycloak.internalUrl + /realms/BankDemoRealm/protocol/openid-connect/certs` (key fetch)

---

## Deploy commands
```bash
# Add Redpanda repo
helm repo add redpanda https://charts.redpanda.com
helm repo update

# Install infra chart
helm install showcase-infra infra/helm/showcase-infra \
  --namespace showcase \
  --set clusterDomain=apps.mycluster.example.com \
  --set postgres.password=<secret> \
  --set keycloak.adminPassword=<secret>

# Wait for infra to be ready, then install apps
helm dependency update infra/helm/showcase-apps
helm install showcase-apps infra/helm/showcase-apps \
  --namespace showcase \
  --set clusterDomain=apps.mycluster.example.com \
  --set otel.collectorEndpoint=http://<actual-collector-service>:4317 \
  --set keycloak.internalUrl=http://keycloak.showcase.svc.cluster.local:8080 \
  --set keycloak.externalUrl=https://keycloak.apps.mycluster.example.com
```

---

## Open items (resolve before implementation)
1. **RHBK Operator CR API version** — check `oc api-resources | grep keycloak` to find the exact
   group/version in use (`k8s.keycloak.org/v2alpha1` or similar).
2. **IBM MQ Operator CR API version** — check `oc api-resources | grep ibm` for `QueueManager` group.
3. **IBM MQ licence** — `QueueManager` CR requires `spec.license.license` (e.g. `L-SMKR-AVHVQM`)
   and `spec.license.use`. Confirm the correct values for the community image.
4. **OTel Collector Service name** — find the actual `OpenTelemetryCollector` CR name and hence
   the Service name (`<cr-name>-collector`). Update `otel.collectorEndpoint` default accordingly.
5. **Tempo endpoint** — find the TempoStack CR and its OTLP distributor address.
6. **KeycloakRealm CR** — confirm the RHBK Operator version supports it and that realm import
   via CR (not `--import-realm` flag) is the right approach.
7. **RHBK Route** — does the Keycloak CR auto-create an OpenShift Route, or do we template one?

---

## Acceptance
- `helm install showcase-infra …` succeeds; all infra pods reach Running.
- `helm install showcase-apps …` succeeds; all app pods reach Running.
- `helm upgrade showcase-apps … --set images.tag=1.0.1` rolls out new pods cleanly.
- `helm uninstall showcase-apps && helm uninstall showcase-infra` leaves cluster clean (PVCs retained by default).
- Smoke test passes via the OpenShift Route for payment-gateway.
- Traces visible in Tempo/Grafana for a submitted payment.
