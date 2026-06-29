# R32 — Helm Charts for Infrastructure and Applications

## Prerequisites
- **R34** must be complete before implementing this task (externalises JWK_SET_URI, CORS origins,
  and clearing simulation delay as env vars so Helm can inject them).

## Goal
Two Helm charts deployable with a single `helm install` per chart. All cluster-specific and
application-level connection strings are Helm values with sensible defaults matching the current
local-dev configuration.

## Cluster facts (confirmed)

| Fact | Value |
|---|---|
| **Operators installed** | RHBK (Keycloak), IBM MQ, OpenTelemetry, Tempo — use CRs, not plain Deployments |
| **Namespace** | Pre-created by user; referenced as `{{ .Values.namespace }}` |
| **Storage class** | Default `thin-csi`; override via `storageClass` value |
| **Cluster domain** | Required; set via `clusterDomain` value |
| **quay.io repos** | Public — no ImagePullSecret |

---

## Chart layout

```
infra/helm/
├── showcase-infra/          # Chart 1 — ALL infrastructure (PostgreSQL, Keycloak, IBM MQ, Redpanda)
│   ├── Chart.yaml           # depends on: redpanda/redpanda (subchart)
│   ├── values.yaml
│   └── templates/
│       ├── NOTES.txt
│       ├── postgres/
│       │   ├── statefulset.yaml
│       │   ├── service.yaml
│       │   ├── secret.yaml
│       │   └── configmap-init.yaml
│       ├── keycloak/
│       │   ├── keycloak-cr.yaml          # Keycloak CR (RHBK Operator)
│       │   ├── keycloakrealm-cr.yaml     # KeycloakRealm CR
│       │   └── configmap-realm.yaml      # realm JSON ConfigMap
│       ├── ibmmq/
│       │   ├── queuemanager-cr.yaml      # QueueManager CR (IBM MQ Operator)
│       │   └── configmap-mqsc.yaml       # MQSC config with MCAUSER('app')
│       └── redpanda/
│           └── topics-job.yaml           # post-install hook: create Kafka topics
│
└── showcase-apps/           # Chart 2 — application microservices + service mesh
    ├── Chart.yaml
    ├── values.yaml
    └── templates/
        ├── NOTES.txt
        ├── _helpers.tpl
        ├── servicemesh/
        │   ├── servicemeshmemberroll.yaml
        │   └── peerauthentication.yaml
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

### Chart.yaml dependencies
```yaml
dependencies:
  - name: redpanda
    version: "5.x.x"        # pin to a stable release
    repository: https://charts.redpanda.com
    condition: redpanda.enabled
```

Run `helm dependency update infra/helm/showcase-infra` before install.

### values.yaml — full specification

```yaml
namespace: showcase
storageClass: thin-csi
clusterDomain: ""    # required — e.g. apps.mycluster.example.com

postgres:
  storageSize: 2Gi
  username: appuser
  password: apppassword     # override in production
  databases:
    accounts: accounts_db
    ledger: ledger_db

keycloak:
  instances: 1
  adminPassword: admin      # override in production
  realm: BankDemoRealm
  # Route host auto-derived: keycloak.{{ .Values.clusterDomain }}
  # Open items: confirm RHBK Operator CR API version and whether Route is auto-created

ibmmq:
  queueManager: QM1
  channel: DEV.APP.SVRCONN
  queue: DEV.QUEUE.CLEARING
  deadLetterQueue: DEV.DEAD.LETTER.QUEUE
  storageSize: 2Gi
  # Open items: confirm QueueManager CR API version and IBM MQ licence string

redpanda:
  enabled: true
  topics:
    paymentApproved: payment-approved
    paymentApprovedDlt: payment-approved.DLT
    paymentCompleted: payment-completed
    paymentCompletedDlt: payment-completed.DLT
  storage:
    persistentVolume:
      enabled: true
      size: 5Gi
      storageClass: thin-csi   # mirrors .Values.storageClass
  # Single-node dev mode: 1 broker, no TLS, no SASL
```

### Keycloak — RHBK Operator CRs
- `Keycloak` CR + `KeycloakRealm` CR referencing the realm JSON ConfigMap.
- Realm JSON is the existing `payment-realm.json` content.
- Route host: `keycloak.{{ .Values.clusterDomain }}` (templated in the CR or separate Route resource).
- Open items: API version, whether operator auto-creates the Route.

### IBM MQ — IBM MQ Operator CR
- `QueueManager` CR with `spec.license.accept: true`.
- MQSC ConfigMap includes `MCAUSER('app')` (R19 fix).
- Open items: API version, licence string (`spec.license.license`, `spec.license.use`).

### PostgreSQL — plain StatefulSet
- `storageClassName: {{ .Values.storageClass }}` in PVC.
- Init ConfigMap creates both databases and grants to `appuser`.

### Redpanda — official subchart
- Single broker, no auth. Topics created by a post-install/post-upgrade `Job`.
- Bootstrap address exposed within the cluster as `{{ .Release.Name }}-redpanda:9092`
  (or whatever the subchart's headless Service name resolves to — confirm after `helm template`).

---

## Chart 2 — showcase-apps

### values.yaml — full specification

Every value below maps directly to an env var already present in the application configs
(all have `${ENV_VAR:default}` bindings). The Helm default matches the current local-dev default.

```yaml
namespace: showcase
clusterDomain: ""    # required

images:
  registry: quay.io/acaglio
  tag: latest
  pullPolicy: Always

# ── Infrastructure connection strings ─────────────────────────────────────────

kafka:
  bootstrapServers: redpanda:9092     # internal cluster address of the Redpanda service
  topics:
    paymentApproved: payment-approved
    paymentApprovedDlt: payment-approved.DLT
    paymentCompleted: payment-completed
    paymentCompletedDlt: payment-completed.DLT
  consumerGroups:
    paymentGateway: payment-gateway-group
    transactionEngine: transaction-engine-group

ibmmq:
  host: ibmmq              # K8s Service name of the IBM MQ QueueManager
  port: 1414
  queueManager: QM1
  channel: DEV.APP.SVRCONN
  queue: DEV.QUEUE.CLEARING
  deadLetterQueue: DEV.DEAD.LETTER.QUEUE
  user: ""
  password: ""             # inject via --set or sealed secret

postgres:
  accountVerifier:
    url: jdbc:postgresql://postgres:5432/accounts_db
    username: appuser
    password: apppassword
  transactionEngine:
    url: jdbc:postgresql://postgres:5432/ledger_db
    username: appuser
    password: apppassword

# ── Keycloak / JWT ────────────────────────────────────────────────────────────

keycloak:
  # JWT issuer claim as seen by the browser (external URL)
  externalUrl: ""    # defaults to https://keycloak.{{ .Values.clusterDomain }}
  # JWK key fetch URL (internal pod-to-pod, avoids external DNS)
  jwkSetUri: ""      # defaults to http://keycloak.{{ .Values.namespace }}.svc.cluster.local:8080/realms/BankDemoRealm/protocol/openid-connect/certs
  realm: BankDemoRealm

# ── gRPC ─────────────────────────────────────────────────────────────────────

grpc:
  accountVerifier:
    address: static://account-verifier:9090   # K8s Service name
    timeoutSeconds: 5

# ── CORS ─────────────────────────────────────────────────────────────────────

cors:
  allowedOrigins: ""    # defaults to https://spa-mobile-app.{{ .Values.clusterDomain }}

# ── Service Mesh ──────────────────────────────────────────────────────────────

serviceMesh:
  enabled: true
  memberRollName: default   # name of the ServiceMeshControlPlane to join

# ── OTel ─────────────────────────────────────────────────────────────────────

otel:
  collectorEndpoint: http://otel-collector:4317   # override with actual Service name in cluster

# ── Clearing house ────────────────────────────────────────────────────────────

clearingHouse:
  simulation:
    delayMsMin: 100
    delayMsMax: 500

# ── Resource requests/limits ──────────────────────────────────────────────────

resources:
  accountVerifier:
    requests: { memory: 256Mi, cpu: 100m }
    limits:   { memory: 512Mi, cpu: 500m }
  paymentGateway:
    requests: { memory: 256Mi, cpu: 100m }
    limits:   { memory: 512Mi, cpu: 500m }
  transactionEngine:
    requests: { memory: 256Mi, cpu: 100m }
    limits:   { memory: 512Mi, cpu: 500m }
  clearingHouse:
    requests: { memory: 256Mi, cpu: 100m }
    limits:   { memory: 512Mi, cpu: 500m }
  spaMobileApp:
    requests: { memory: 64Mi,  cpu: 50m  }
    limits:   { memory: 128Mi, cpu: 200m }
```

### Env var mapping per service

| Service | Env var | Helm value path |
|---|---|---|
| account-verifier | `KAFKA_BOOTSTRAP_SERVERS` | `kafka.bootstrapServers` |
| account-verifier | `KAFKA_TOPIC_PAYMENT_APPROVED` | `kafka.topics.paymentApproved` |
| account-verifier | `KAFKA_TOPIC_PAYMENT_APPROVED_DLT` | `kafka.topics.paymentApprovedDlt` |
| account-verifier | `QUARKUS_DATASOURCE_JDBC_URL` | `postgres.accountVerifier.url` |
| account-verifier | `QUARKUS_DATASOURCE_USERNAME` | `postgres.accountVerifier.username` |
| account-verifier | `QUARKUS_DATASOURCE_PASSWORD` | `postgres.accountVerifier.password` |
| account-verifier | `QUARKUS_GRPC_SERVER_PORT` | hardcoded 9090 in Service |
| payment-gateway | `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `kafka.bootstrapServers` |
| payment-gateway | `SPRING_KAFKA_CONSUMER_GROUP_ID` | `kafka.consumerGroups.paymentGateway` |
| payment-gateway | `APP_KAFKA_TOPICS_PAYMENT_COMPLETED` | `kafka.topics.paymentCompleted` |
| payment-gateway | `APP_KAFKA_TOPICS_PAYMENT_COMPLETED_DLT` | `kafka.topics.paymentCompletedDlt` |
| payment-gateway | `GRPC_CLIENT_ACCOUNT_VERIFIER_ADDRESS` | `grpc.accountVerifier.address` |
| payment-gateway | `GRPC_ACCOUNT_VERIFIER_TIMEOUT_SECONDS` | `grpc.accountVerifier.timeoutSeconds` |
| payment-gateway | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | computed from `keycloak.externalUrl` + `keycloak.realm` |
| payment-gateway | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` | computed from `keycloak.jwkSetUri` |
| payment-gateway | `APP_CORS_ALLOWED_ORIGINS` | computed from `cors.allowedOrigins` |
| transaction-engine | `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `kafka.bootstrapServers` |
| transaction-engine | `SPRING_KAFKA_CONSUMER_GROUP_ID` | `kafka.consumerGroups.transactionEngine` |
| transaction-engine | `APP_KAFKA_TOPICS_PAYMENT_APPROVED` | `kafka.topics.paymentApproved` |
| transaction-engine | `APP_KAFKA_TOPICS_PAYMENT_APPROVED_DLT` | `kafka.topics.paymentApprovedDlt` |
| transaction-engine | `APP_MQ_QUEUES_CLEARING` | `ibmmq.queue` |
| transaction-engine | `APP_MQ_QUEUES_DEAD_LETTER` | `ibmmq.deadLetterQueue` |
| transaction-engine | `IBM_MQ_CONN_NAME` | computed: `{{ ibmmq.host }}({{ ibmmq.port }})` |
| transaction-engine | `IBM_MQ_QUEUE_MANAGER` | `ibmmq.queueManager` |
| transaction-engine | `IBM_MQ_CHANNEL` | `ibmmq.channel` |
| transaction-engine | `IBM_MQ_USER` | `ibmmq.user` |
| transaction-engine | `IBM_MQ_PASSWORD` | `ibmmq.password` |
| transaction-engine | `SPRING_DATASOURCE_URL` | `postgres.transactionEngine.url` |
| transaction-engine | `SPRING_DATASOURCE_USERNAME` | `postgres.transactionEngine.username` |
| transaction-engine | `SPRING_DATASOURCE_PASSWORD` | `postgres.transactionEngine.password` |
| clearing-house | `KAFKA_BOOTSTRAP_SERVERS` | `kafka.bootstrapServers` |
| clearing-house | `KAFKA_TOPIC_PAYMENT_COMPLETED` | `kafka.topics.paymentCompleted` |
| clearing-house | `KAFKA_TOPIC_PAYMENT_COMPLETED_DLT` | `kafka.topics.paymentCompletedDlt` |
| clearing-house | `IBM_MQ_HOST` | `ibmmq.host` |
| clearing-house | `IBM_MQ_PORT` | `ibmmq.port` |
| clearing-house | `IBM_MQ_QUEUE_MANAGER` | `ibmmq.queueManager` |
| clearing-house | `IBM_MQ_CHANNEL` | `ibmmq.channel` |
| clearing-house | `IBM_MQ_QUEUE_NAME` | `ibmmq.queue` |
| clearing-house | `IBM_MQ_USER` | `ibmmq.user` |
| clearing-house | `IBM_MQ_PASSWORD` | `ibmmq.password` |
| clearing-house | `CLEARING_SIMULATION_DELAY_MS_MIN` | `clearingHouse.simulation.delayMsMin` |
| clearing-house | `CLEARING_SIMULATION_DELAY_MS_MAX` | `clearingHouse.simulation.delayMsMax` |

### _helpers.tpl
- `showcase.labels` — `app.kubernetes.io/name`, `app.kubernetes.io/version`, `helm.sh/chart`
- `showcase.otelEnv` — named template (service name arg) emitting all 6 OTel env vars; used in every Java Deployment so the block is not copy-pasted
- `showcase.keycloakIssuerUri` — `keycloak.externalUrl/realms/keycloak.realm`, defaulting to `https://keycloak.clusterDomain/realms/...`
- `showcase.keycloakJwkSetUri` — `keycloak.jwkSetUri`, defaulting to `http://keycloak.namespace.svc.cluster.local:8080/realms/.../certs`
- `showcase.corsOrigins` — `cors.allowedOrigins`, defaulting to `https://spa-mobile-app.clusterDomain`
- `showcase.mqConnName` — `"{{ ibmmq.host }}({{ ibmmq.port }})"` — Spring Boot expects `host(port)` syntax

### Service Mesh templates
- `ServiceMeshMemberRoll` — adds namespace to mesh (R02 fix), gated on `serviceMesh.enabled`
- `PeerAuthentication` with `mode: STRICT` (R26 fix)

### SPA — nginx-unprivileged
- Image: `nginxinc/nginx-unprivileged:1.27-alpine` (R10 fix — non-root UID 101)
- Route host: `spa-mobile-app.{{ .Values.clusterDomain }}`

---

## Deploy commands

```bash
# Add Redpanda Helm repo
helm repo add redpanda https://charts.redpanda.com
helm repo update

# Install infra (includes Redpanda, PostgreSQL, Keycloak, IBM MQ)
helm dependency update infra/helm/showcase-infra
helm install showcase-infra infra/helm/showcase-infra \
  --namespace showcase \
  --set clusterDomain=apps.mycluster.example.com \
  --set postgres.password=<secret> \
  --set keycloak.adminPassword=<secret>

# Wait for infra to be ready, then install apps
helm install showcase-apps infra/helm/showcase-apps \
  --namespace showcase \
  --set clusterDomain=apps.mycluster.example.com \
  --set otel.collectorEndpoint=http://<collector-service>:4317 \
  --set ibmmq.password=<mq-password>
```

---

## Open items (resolve by querying the cluster before implementing)

1. **RHBK Operator CR API version** — `oc api-resources | grep keycloak`
2. **RHBK Route** — does the `Keycloak` CR auto-create an OpenShift Route, or do we template one?
3. **KeycloakRealm CR** — confirm the installed operator version supports it
4. **IBM MQ Operator CR API version** — `oc api-resources | grep ibm`
5. **IBM MQ licence string** — `spec.license.license` and `spec.license.use` for the community image (`icr.io/ibm-messaging/mq:9.3.5.1-r2`)
6. **Redpanda Service name** — run `helm template showcase-infra infra/helm/showcase-infra | grep 'kind: Service' -A5` to find the exact bootstrap address the subchart creates; update `kafka.bootstrapServers` default accordingly
7. **OTel Collector Service name** — `oc get opentelemetrycollectors -n <otel-namespace>` to find the CR name → Service name is `<cr-name>-collector`
8. **Tempo OTLP endpoint** — `oc get tempostacks -A` or `oc get tempomonolithics -A`

---

## Acceptance
- `helm install showcase-infra …` succeeds; all infra pods reach Running/Ready.
- `helm install showcase-apps …` succeeds; all app pods reach Running/Ready.
- `helm upgrade showcase-apps … --set images.tag=<sha>` rolls out new pods cleanly.
- `helm uninstall showcase-apps && helm uninstall showcase-infra` leaves cluster clean (PVCs retained).
- Smoke test passes via the OpenShift Route for payment-gateway.
- Traces visible in Tempo/Grafana for a submitted payment.
