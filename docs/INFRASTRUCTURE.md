# INFRASTRUCTURE.md — Infrastructure Specifications

> This document covers all infrastructure components: Keycloak, PostgreSQL, Redpanda (Kafka), and IBM MQ.
> It defines configurations for both the **local Docker Compose stack** and **OpenShift K8s manifests**.

---

## 1. Infrastructure Port Map (Local Docker Compose)

| Component | Service Name | Port(s) | Protocol |
|---|---|---|---|
| Keycloak | `keycloak` | `8080` | HTTP |
| PostgreSQL | `postgres` | `5432` | TCP |
| Redpanda | `redpanda` | `9092` (Kafka), `8081` (Schema Registry), `9644` (Admin) | TCP / HTTP |
| IBM MQ | `ibmmq` | `1414` (MQ), `9443` (Web Console) | TCP / HTTPS |
| payment-gateway | `payment-gateway` | `8090→8080` | HTTP |
| account-verifier | `account-verifier` | `9090` (gRPC), `8091→8080` (health) | TCP / HTTP |
| transaction-engine | `transaction-engine` | `8092→8080` | HTTP |
| clearing-house | `clearing-house` | `8093→8080` | HTTP |
| spa-mobile-app | `spa-mobile-app` | `3000→80` | HTTP |

> Host-mapped ports for the Java services use `80xx` offsets to avoid collisions. Container-internal ports are always standard (8080, 9090).

---

## 2. Keycloak

### Container Image

```
quay.io/keycloak/keycloak:25.0.6
```

### Realm Configuration

A pre-built realm export (`payment-realm.json`) is mounted into the container and imported on first startup. The realm JSON must define:

**Realm name:** `BankDemoRealm`

**Clients:**

| Client ID | Type | Settings |
|---|---|---|
| `spa-payment-client` | Public | Standard Flow, PKCE enabled (`S256`), redirect URIs: `http://localhost:3000/*`, valid post-logout URIs: `http://localhost:3000/*` |
| `backend-gateway-service` | Bearer-only | Used by `payment-gateway` for JWKS validation only |

**Roles (realm-level):**

| Role | Assigned to |
|---|---|
| `payment-init` | Test user `testuser` |

**Test Users:**

| Username | Password | Roles |
|---|---|---|
| `testuser` | `password` | `payment-init` |

### Startup Command

```
start-dev --import-realm
```

Mount `payment-realm.json` to `/opt/keycloak/data/import/payment-realm.json`.

### Environment Variables

```yaml
KEYCLOAK_ADMIN: admin
KEYCLOAK_ADMIN_PASSWORD: admin
KC_HTTP_PORT: 8080
```

### K8s Notes

Use a `Deployment` + `ClusterIP Service`. Mount the realm JSON via a `ConfigMap`. For OpenShift, create a `Route` for external access.

---

## 3. PostgreSQL

### Container Image

```
docker.io/library/postgres:16-alpine
```

### Initialization

Mount a SQL init script to `/docker-entrypoint-initdb.d/init.sql`. This script runs **once** on first container start and creates the two application databases and a shared application user:

```sql
CREATE DATABASE accounts_db;
CREATE DATABASE ledger_db;

CREATE USER appuser WITH PASSWORD 'apppassword';

GRANT ALL PRIVILEGES ON DATABASE accounts_db TO appuser;
GRANT ALL PRIVILEGES ON DATABASE ledger_db TO appuser;

-- Required for Flyway schema management
\c accounts_db;
GRANT ALL ON SCHEMA public TO appuser;

\c ledger_db;
GRANT ALL ON SCHEMA public TO appuser;
```

### Environment Variables

```yaml
POSTGRES_USER: postgres
POSTGRES_PASSWORD: postgrespassword
POSTGRES_DB: postgres
```

### Schema Management

Flyway migrations run on **application startup** (not at the database level). Each service's `src/main/resources/db/migration/` contains its own versioned SQL scripts.

| Service | Database | Migration path |
|---|---|---|
| `account-verifier` | `accounts_db` | `apps/account-verifier/src/main/resources/db/migration/` |
| `transaction-engine` | `ledger_db` | `apps/transaction-engine/src/main/resources/db/migration/` |

### K8s Notes

Use a `StatefulSet` with a `PersistentVolumeClaim` (1Gi minimum). The init script is mounted via a `ConfigMap`.

---

## 4. Redpanda (Kafka-compatible)

### Container Image

```
docker.redpanda.com/redpandadata/redpanda:v24.2.7
```

### Startup Command

```bash
redpanda start \
  --mode dev-container \
  --kafka-addr plaintext://0.0.0.0:9092 \
  --advertise-kafka-addr plaintext://redpanda:9092 \
  --schema-registry-addr http://0.0.0.0:8081 \
  --pandaproxy-addr http://0.0.0.0:8082 \
  --rpc-addr redpanda:33145 \
  --advertise-rpc-addr redpanda:33145
```

### Required Topics

> **Topic names are environment-specific configuration.** The names in the table below are the defaults. Override them via env vars on both the Redpanda init job and every application service container. The init job and K8s Job must read these env vars rather than hardcoding names.

| Topic (default name) | Env var (services) | Partitions | Replication Factor | Purpose |
|---|---|---|---|---|
| `payment-approved` | `KAFKA_TOPIC_PAYMENT_APPROVED` / `APP_KAFKA_TOPICS_PAYMENT_APPROVED` | 3 | 1 | account-verifier → transaction-engine |
| `payment-completed` | `KAFKA_TOPIC_PAYMENT_COMPLETED` / `APP_KAFKA_TOPICS_PAYMENT_COMPLETED` | 3 | 1 | clearing-house → payment-gateway |
| `payment-approved.DLT` | `KAFKA_TOPIC_PAYMENT_APPROVED_DLT` / `APP_KAFKA_TOPICS_PAYMENT_APPROVED_DLT` | 1 | 1 | Dead-letter for `payment-approved` failures |
| `payment-completed.DLT` | `KAFKA_TOPIC_PAYMENT_COMPLETED_DLT` / `APP_KAFKA_TOPICS_PAYMENT_COMPLETED_DLT` | 1 | 1 | Dead-letter for `payment-completed` failures |

**Topic creation commands (using defaults):**

```bash
TOPIC_APPROVED=${KAFKA_TOPIC_PAYMENT_APPROVED:-payment-approved}
TOPIC_COMPLETED=${KAFKA_TOPIC_PAYMENT_COMPLETED:-payment-completed}
rpk topic create "$TOPIC_APPROVED" --partitions 3 --replicas 1
rpk topic create "$TOPIC_COMPLETED" --partitions 3 --replicas 1
rpk topic create "${KAFKA_TOPIC_PAYMENT_APPROVED_DLT:-${TOPIC_APPROVED}.DLT}" --partitions 1 --replicas 1
rpk topic create "${KAFKA_TOPIC_PAYMENT_COMPLETED_DLT:-${TOPIC_COMPLETED}.DLT}" --partitions 1 --replicas 1
```

### K8s Notes

Use a `Deployment` (single-node dev config). Expose via a `ClusterIP Service` with named ports for Kafka (`9092`) and Admin API (`9644`). Topic creation is handled by an init `Job`; the Job spec must source topic names from a `ConfigMap` backed by the same env vars.

---

## 5. IBM MQ

### Container Image

```
icr.io/ibm-mq/mq:9.3.5.1-r2
```

### Environment Variables

```yaml
LICENSE: accept
MQ_QMGR_NAME: QM1
MQ_APP_PASSWORD: ""
MQ_ADMIN_PASSWORD: admin
MQ_ENABLE_METRICS: "false"
```

### MQSC Configuration

Mount the following script to `/etc/mqm/config.mqsc`. IBM MQ runs this on startup to configure the queue manager.

> **Queue names are environment-specific configuration.** `DEV.QUEUE.CLEARING` and `DEV.DEAD.LETTER.QUEUE` are defaults only. Both the MQSC script (defining queues at the broker level) and the application services referencing them must derive queue names from env vars (`APP_MQ_QUEUES_CLEARING`, `APP_MQ_QUEUES_DEAD_LETTER`) so names can differ across environments without code changes. For Docker Compose and K8s, generate the MQSC from a template substituting these env vars at container startup.

```mqsc
DEFINE CHANNEL(DEV.APP.SVRCONN) CHLTYPE(SVRCONN) TRPTYPE(TCP) REPLACE
DEFINE QLOCAL(DEV.QUEUE.CLEARING) REPLACE
DEFINE QLOCAL(DEV.DEAD.LETTER.QUEUE) REPLACE
ALTER QMGR DEADQ(DEV.DEAD.LETTER.QUEUE)
ALTER QMGR CHLAUTH(DISABLED)
ALTER AUTHINFO(SYSTEM.DEFAULT.AUTHINFO.IDPWOS) AUTHTYPE(IDPWOS) ADOPTCTX(YES) CHCKCLNT(OPTIONAL) CHCKLOCL(OPTIONAL) AUTHENMD(OS)
REFRESH SECURITY TYPE(CONNAUTH)
```

> `CHLAUTH(DISABLED)` and `AUTHENMD(OS)` with `CHCKCLNT(OPTIONAL)` allow unauthenticated connections for development. Add proper channel authentication for production.

### K8s Notes

Use a `StatefulSet` with a `PersistentVolumeClaim`. Mount the MQSC script via a `ConfigMap` at `/etc/mqm/config.mqsc`. Expose ports `1414` (MQ) and `9443` (console) via a `ClusterIP Service`.

---

## 6. Docker Compose (`infra/docker/docker-compose.yml`)

The Compose file defines the **complete local development stack** including all infrastructure and application services.

### Key Design Rules

- All services are on a single `showcase-net` bridge network.
- Infrastructure services (`keycloak`, `postgres`, `redpanda`, `ibmmq`) start before application services via `depends_on: condition: service_healthy`.
- Each infrastructure service defines a `healthcheck`.
- Application services include `JAVA_TOOL_OPTIONS` pointing to the OTel Agent (see [`TRACING.md`](TRACING.md)).
- For local dev, the OTel exporter can be set to `none` or point to a locally running Grafana Alloy/Collector.

### Healthcheck Examples

```yaml
# Keycloak
healthcheck:
  test: ["CMD", "curl", "-f", "http://localhost:8080/health/ready"]
  interval: 15s
  timeout: 5s
  retries: 10
  start_period: 60s

# PostgreSQL
healthcheck:
  test: ["CMD-SHELL", "pg_isready -U postgres"]
  interval: 5s
  timeout: 3s
  retries: 10

# Redpanda
healthcheck:
  test: ["CMD", "rpk", "cluster", "health"]
  interval: 10s
  timeout: 5s
  retries: 10
  start_period: 30s

# IBM MQ
healthcheck:
  test: ["CMD", "dspmq", "-m", "QM1"]
  interval: 10s
  timeout: 5s
  retries: 15
  start_period: 45s
```

### Topic Initialization

Add a one-shot service that creates Kafka topics after Redpanda is healthy:

```yaml
redpanda-init:
  image: docker.redpanda.com/redpandadata/redpanda:v24.2.7
  depends_on:
    redpanda:
      condition: service_healthy
  entrypoint: >
    bash -c "
      rpk topic create payment-approved --partitions 3 --replicas 1 --brokers redpanda:9092;
      rpk topic create payment-completed --partitions 3 --replicas 1 --brokers redpanda:9092;
      rpk topic create payment-approved.DLT --partitions 1 --replicas 1 --brokers redpanda:9092;
      rpk topic create payment-completed.DLT --partitions 1 --replicas 1 --brokers redpanda:9092;
    "
  restart: "no"
```

---

## 7. Kubernetes Manifests (`infra/k8s/`)

### Directory Layout

```
infra/k8s/
├── infra/
│   ├── namespace.yaml               # Namespace: showcase
│   ├── keycloak/
│   │   ├── configmap-realm.yaml     # payment-realm.json as ConfigMap
│   │   ├── deployment.yaml
│   │   ├── service.yaml
│   │   └── route.yaml               # OpenShift Route
│   ├── postgres/
│   │   ├── configmap-init.yaml      # init.sql as ConfigMap
│   │   ├── secret.yaml              # DB credentials
│   │   ├── statefulset.yaml
│   │   ├── pvc.yaml
│   │   └── service.yaml
│   ├── redpanda/
│   │   ├── deployment.yaml
│   │   ├── service.yaml
│   │   └── job-create-topics.yaml   # One-shot Job for topic creation
│   └── ibmmq/
│       ├── configmap-mqsc.yaml      # config.mqsc as ConfigMap
│       ├── statefulset.yaml
│       ├── pvc.yaml
│       └── service.yaml
└── apps/
    ├── payment-gateway/
    │   ├── deployment.yaml          # Includes OTel env vars
    │   ├── service.yaml
    │   └── route.yaml
    ├── account-verifier/
    │   ├── deployment.yaml
    │   └── service.yaml             # Named ports: grpc (9090), http (8080)
    ├── transaction-engine/
    │   ├── deployment.yaml
    │   └── service.yaml
    └── clearing-house/
        ├── deployment.yaml
        └── service.yaml
```

### Standard App Deployment Template

Every app `Deployment` follows this pattern:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: payment-gateway
  namespace: showcase
spec:
  replicas: 1
  selector:
    matchLabels:
      app: payment-gateway
  template:
    metadata:
      labels:
        app: payment-gateway
    spec:
      containers:
        - name: payment-gateway
          image: quay.io/acaglio/payment-gateway:latest
          ports:
            - containerPort: 8080
          env:
            # OTel Agent (see TRACING.md for full env var list)
            - name: JAVA_TOOL_OPTIONS
              value: "-javaagent:/opt/otel/opentelemetry-javaagent.jar"
            - name: OTEL_SERVICE_NAME
              value: "payment-gateway"
            - name: OTEL_TRACES_EXPORTER
              value: "otlp"
            - name: OTEL_EXPORTER_OTLP_ENDPOINT
              value: "http://otel-collector:4317"
            - name: OTEL_METRICS_EXPORTER
              value: "none"
            # Application-specific env vars...
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 60
            periodSeconds: 15
```

### Namespace

All resources use namespace `showcase`.

### OpenShift Routes

Each user-facing service (`spa-mobile-app`, `payment-gateway`) gets an OpenShift `Route` for external access. Backend services use `ClusterIP` only.

### Secrets Management

Database credentials and MQ credentials are stored in `Secret` resources (not `ConfigMap`). Reference them via `envFrom` or `valueFrom.secretKeyRef` in Deployments.
