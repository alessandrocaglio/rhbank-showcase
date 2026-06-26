# Observability Showcase — Distributed Tracing Across a Polyglot Microservice Mesh

A **Red Hat labs engineering showcase** demonstrating unbroken, end-to-end distributed trace propagation across every protocol boundary in a simulated digital banking payment pipeline.

The domain is secondary. The engineering objective is to prove that a single W3C `traceparent` trace ID travels — without breaks — across **Spring Boot** and **Quarkus** services, through **Redpanda (Kafka)**, and through **IBM MQ**, with every hop visible in **Grafana Tempo** on **Red Hat OpenShift 4.x with OpenShift Service Mesh 3**.

---

## Architecture

```text
                          [ KEYCLOAK :8080 ]
                                 │
               (Issue JWT)       │    (JWKS validation)
                           ▼     ▼
┌─────────────────────────────────────────────────────────────────┐
│                    spa-mobile-app (:80)                         │
│                  Vue 3 · Vite · Nginx                           │
└─────┬───────────────────────────────────────────────────▲───────┘
      │ (1) POST /api/v1/payments                         │ (8) SSE
      │     Authorization: Bearer <JWT>                   │     /api/v1/payments/stream/{txId}
      ▼                                                   │
┌─────────────────────────────────────────────────────────┴───────┐
│                  payment-gateway (:8080)                         │
│                    Spring Boot 3.3.x                            │
└─────┬───────────────────────────────────────────────────▲───────┘
      │ (2) gRPC — AccountService/VerifyAccount           │ (7) Kafka consumer
      │     traceparent in gRPC metadata                  │     topic: payment-completed
      ▼                                                   │
┌─────────────────────────────────────────────────────────┴───────┐
│               account-verifier (:9090 gRPC / :8080 health)      │
│                     Quarkus 3.15.x                              │
│              accounts_db  (PostgreSQL + Flyway)                 │
└─────┬───────────────────────────────────────────────────────────┘
      │ (3) Kafka producer — topic: payment-approved
      │     traceparent in Kafka record headers (auto, OTel agent)
      ▼
┌─────────────────────────────────────────────────────────────────┐
│                 transaction-engine (:8080)                       │
│                    Spring Boot 3.3.x                            │
│               ledger_db  (PostgreSQL + Flyway)                  │
└─────┬───────────────────────────────────────────────────────────┘
      │ (4) JMS producer — queue: DEV.QUEUE.CLEARING (IBM MQ)
      │     traceparent as JMS String property  ← manual safety net
      ▼
┌─────────────────────────────────────────────────────────────────┐
│                   clearing-house (:8080)                         │
│                     Quarkus 3.15.x                              │
└─────┬───────────────────────────────────────────────────────────┘
      └─ (5+6) JMS consumer → simulate clearing → Kafka producer
               topic: payment-completed
               traceparent restored via TextMapGetter  ← manual safety net
```

---

## Transaction Lifecycle

| Step | Actor | What happens |
|---|---|---|
| 1 | `spa-mobile-app` | Authenticates with Keycloak via OIDC Authorization Code + PKCE. Opens SSE stream before posting. |
| 2 | `payment-gateway` | Validates JWT (`payment-init` role required). Generates `txId` (UUID). Returns **HTTP 202** immediately. Calls `account-verifier` via gRPC. |
| 3 | `account-verifier` | Validates balance in `accounts_db`, applies hold atomically. Publishes `PaymentApprovedEvent` to `payment-approved` Kafka topic. |
| 4 | `transaction-engine` | Consumes `payment-approved`. Writes immutable record to `ledger_db`. Publishes JMS message to IBM MQ queue `DEV.QUEUE.CLEARING`. |
| 5–6 | `clearing-house` | Consumes JMS message. Simulates SWIFT/Fedwire clearing (95 % success rate, 100–500 ms delay). Publishes `PaymentCompletedEvent` to `payment-completed` Kafka topic. |
| 7 | `payment-gateway` | Consumes `payment-completed`. Resolves in-memory `SseEmitter` keyed on `txId`. |
| 8 | `spa-mobile-app` | Receives `COMPLETED` or `FAILED` status in real time over SSE. |

---

## Service Inventory

| Service | Framework | Container port(s) | Language | Role |
|---|---|---|---|---|
| `spa-mobile-app` | Vue 3 + Vite + Nginx | `80` | JavaScript | Frontend OIDC client |
| `payment-gateway` | Spring Boot 3.3.5 | `8080` HTTP | Java 21 | Edge API; gRPC client; Kafka consumer; SSE emitter |
| `account-verifier` | Quarkus 3.15.1 | `9090` gRPC · `8080` health | Java 21 | Account validation; Kafka producer |
| `transaction-engine` | Spring Boot 3.3.5 | `8080` management | Java 21 | Kafka consumer; ledger persistence; IBM MQ producer |
| `clearing-house` | Quarkus 3.15.1 | `8080` health | Java 21 | IBM MQ consumer; Kafka producer |
| `grpc-api` *(library)* | Protobuf 3 / gRPC Java | — | Java 21 | Shared proto stubs consumed by gateway + verifier |

### Infrastructure

| Component | Image | Local port(s) | Purpose |
|---|---|---|---|
| Keycloak | `quay.io/keycloak/keycloak:25.0.6` | `8080` | OIDC identity provider |
| PostgreSQL | `postgres:16-alpine` | `5432` | Shared DB host (`accounts_db`, `ledger_db`) |
| Redpanda | `redpandadata/redpanda:v24.2.7` | `9092` Kafka · `8081` Schema Registry · `9644` Admin | Kafka-compatible event streaming |
| IBM MQ | `icr.io/ibm-mq/mq:9.3.5.1-r2` | `1414` MQ · `9443` console | Enterprise JMS |

### Kafka Topics

| Topic | Partitions | Producer | Consumer |
|---|---|---|---|
| `payment-approved` | 3 | `account-verifier` | `transaction-engine` |
| `payment-completed` | 3 | `clearing-house` | `payment-gateway` |
| `payment-approved.DLT` | 1 | Spring Kafka error handler | — (monitoring) |
| `payment-completed.DLT` | 1 | Spring Kafka error handler | — (monitoring) |

---

## Tech Stack at a Glance

| Concern | Technology |
|---|---|
| Distributed tracing | OpenTelemetry Java Agent 2.6.x — auto-instrumentation, no SDK vendor lock-in |
| Propagation format | W3C Trace Context (`traceparent` / `tracestate`) |
| Trace backend | Grafana Tempo via OTel Collector (OTLP/gRPC) |
| Service mesh | Red Hat OpenShift Service Mesh 3 (Istio) |
| Identity | Keycloak 25 — Authorization Code + PKCE |
| gRPC contract | Protobuf 3, shared `grpc-api` Maven module |
| Build | Maven 3.9.x + `./mvnw` wrapper |
| Container images | Google Jib — no Docker daemon needed for Java services |
| Base runtime image | `ubi9/openjdk-21-runtime:latest` |
| Coverage gate | JaCoCo ≥ 80 % line coverage on every module (build fails otherwise) |

---

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | **21** | `JAVA_HOME` must point to a Java 21 JDK |
| Maven | 3.9.x | Use `./mvnw`; no separate install needed |
| Docker or Podman | 24+ | For the local infrastructure stack |
| Node.js | 20 LTS | Frontend development only |
| `oc` CLI | 4.14+ | OpenShift deployment only |

---

## Quick Start — Local Development

### 1. Clone

```bash
git clone <repo-url> showcase && cd showcase
```

### 2. Start local infrastructure

```bash
docker-compose -f infra/docker/docker-compose.yml up -d
```

This starts Keycloak (with `BankDemoRealm` pre-imported), PostgreSQL (with `accounts_db` and `ledger_db` initialised), Redpanda (with all four Kafka topics auto-created by the `redpanda-init` one-shot container), and IBM MQ.

Wait until all services are healthy:

```bash
docker-compose -f infra/docker/docker-compose.yml ps
# Every service should show "healthy" or "exited (0)" for redpanda-init
```

Startup times: Keycloak ~60 s, IBM MQ ~45 s.

### 3. Build all Java modules

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./mvnw clean package -DskipTests
```

### 4. Run a Java service (example: account-verifier in dev mode)

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  ./mvnw -pl apps/account-verifier quarkus:dev
```

All services pick up their configuration from `application.properties` / `application.yml` with defaults that target the local Docker Compose addresses. No extra environment variables needed for a standard local run.

| Service | Run command |
|---|---|
| `payment-gateway` | `./mvnw -pl apps/payment-gateway spring-boot:run` |
| `account-verifier` | `./mvnw -pl apps/account-verifier quarkus:dev` |
| `transaction-engine` | `./mvnw -pl apps/transaction-engine spring-boot:run` |
| `clearing-house` | `./mvnw -pl apps/clearing-house quarkus:dev` |

### 5. Start the frontend

```bash
cd apps/spa-mobile-app
npm install && npm run dev    # http://localhost:5173
```

Log in with **`testuser` / `password`** (pre-seeded in the Keycloak realm).

### 6. Tear down

```bash
docker-compose -f infra/docker/docker-compose.yml down -v
```

---

## Running Tests

### All modules with coverage enforcement

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./mvnw verify
```

JaCoCo enforces **≥ 80 % line coverage** on every module. The build fails below this threshold. Coverage reports land in each module's `target/jacoco-report/`.

### Single module

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./mvnw test -pl apps/payment-gateway
```

### Frontend

```bash
cd apps/spa-mobile-app
npm test            # Vitest unit tests
```

> Tests run entirely without external services. Java tests use H2 in-memory databases, Kafka `@EmbeddedKafka`, SmallRye `InMemoryConnector`, and Mockito stubs for IBM MQ. No Docker required to run the test suite.

---

## Building Container Images

Java services use **Google Jib** — images are built and pushed without a local Docker daemon.

```bash
# Push all Java services to quay.io/acaglio
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  ./mvnw -pl apps/payment-gateway   compile jib:build -Dimage=quay.io/acaglio/payment-gateway:latest
  ./mvnw -pl apps/account-verifier  compile jib:build -Dimage=quay.io/acaglio/account-verifier:latest
  ./mvnw -pl apps/transaction-engine compile jib:build -Dimage=quay.io/acaglio/transaction-engine:latest
  ./mvnw -pl apps/clearing-house    compile jib:build -Dimage=quay.io/acaglio/clearing-house:latest
```

The OTel Java Agent (`opentelemetry-javaagent.jar`) is embedded at `/opt/otel/` in every Java image and activated via `JAVA_TOOL_OPTIONS`.

Frontend (requires Docker/Podman):

```bash
docker build -t quay.io/acaglio/spa-mobile-app:latest apps/spa-mobile-app/
docker push quay.io/acaglio/spa-mobile-app:latest
```

---

## Deploying to OpenShift

### Cluster requirements

- OpenShift 4.14+
- **Red Hat OpenShift Service Mesh 3** operator installed
- **Red Hat build of OpenTelemetry** operator installed (or a standalone OTel Collector reachable at `http://otel-collector:4317`)
- **Grafana Tempo** deployed and accessible from the Collector

### Deploy

```bash
oc login <cluster-api-url>

# Infrastructure (Keycloak, PostgreSQL, Redpanda, IBM MQ)
oc apply -f infra/k8s/infra/
oc get pods -n showcase -w      # wait until all healthy

# Applications
oc apply -f infra/k8s/apps/
oc get route spa-mobile-app -n showcase   # get the frontend URL
```

All application Deployments are pre-configured with the OTel environment variables pointing to `http://otel-collector:4317`. Adjust `OTEL_EXPORTER_OTLP_ENDPOINT` to match your Collector's in-cluster address.

### OTel Collector reference config

`infra/otel/collector-config.yaml` contains a ready-to-use Collector configuration that receives OTLP on `4317` and forwards to Grafana Tempo. When using the Red Hat OpenTelemetry Operator, create an `OpenTelemetryCollector` CR instead of applying this file directly.

---

## Distributed Tracing — How It Works

### Agent-only strategy

All Java services attach the **OpenTelemetry Java Agent** at runtime via `JAVA_TOOL_OPTIONS`. Application code declares only `opentelemetry-api` for manual span attribute tagging — no SDK dependency, no vendor lock-in.

### Cross-boundary propagation

| Boundary | Transport | How propagation works | Manual code? |
|---|---|---|---|
| HTTP → gRPC | gRPC metadata (HTTP/2 headers) | OTel agent intercepts outbound gRPC calls and injects `traceparent` automatically | No |
| gRPC → Kafka | Kafka record headers | OTel agent intercepts the Kafka producer client and injects `traceparent` into headers | No |
| Kafka → IBM MQ JMS | JMS String property | **Agent cannot reliably intercept IBM MQ JMS.** `transaction-engine` explicitly calls `message.setStringProperty("traceparent", ...)` using `GlobalOpenTelemetry.getPropagators().inject(...)` | **Yes** |
| IBM MQ JMS → Kafka | Restored in `clearing-house` | `clearing-house` reads the JMS `traceparent` property via a `TextMapGetter<TextMessage>`, calls `context.extract(...)`, and starts a child span with `setParent(extractedContext)` | **Yes** |
| Kafka → SSE | Internal (`payment-gateway`) | Same service — OTel agent links spans automatically | No |

The manual JMS propagation is the critical safety net defined in the [W3C Trace Context specification](https://www.w3.org/TR/trace-context/). See `docs/TRACING.md` for the exact code patterns.

### Custom span attributes

Every service tags its spans with business-level attributes queryable in Grafana Tempo:

| Attribute | Set by |
|---|---|
| `bank.payment.transaction_id` | All services |
| `bank.payment.source_account` | `payment-gateway` |
| `bank.payment.amount` | `payment-gateway` |
| `bank.payment.currency` | `payment-gateway` |
| `bank.account.approved` | `account-verifier` |
| `bank.ledger.record_id` | `transaction-engine` |
| `bank.clearing.status` | `clearing-house` |

### Querying a full trace in Tempo

1. Copy the `X-B3-TraceId` response header from the `POST /api/v1/payments` call (or grab it from the Keycloak access log).
2. In Grafana → Explore → Tempo, search by TraceID or filter on `bank.payment.transaction_id = "<txId>"`.
3. Expect **five services** in the waterfall: `payment-gateway` → `account-verifier` → `transaction-engine` → `clearing-house` → `payment-gateway` (Kafka consumer), all sharing one root trace.

---

## Project Structure

```
.
├── README.md
├── CLAUDE.md                        # AI generation entrypoint + sub-spec index
├── pom.xml                          # Maven aggregator (Java 21, JaCoCo, Jib)
├── mvnw / mvnw.cmd                  # Maven wrapper
├── .gitignore
│
├── docs/
│   ├── SERVICES.md                  # Per-service: deps, env vars, API contracts, schemas
│   ├── INFRASTRUCTURE.md            # Keycloak, PostgreSQL, Redpanda, IBM MQ, K8s
│   ├── TRACING.md                   # OTel agent config and propagation patterns
│   └── CONVENTIONS.md               # Coding standards, testing requirements
│
├── apps/
│   ├── grpc-api/                    # Shared Protobuf definitions + generated Java stubs
│   │   └── src/main/proto/account.proto
│   ├── spa-mobile-app/              # Vue 3 + Vite frontend (Red Hat branding)
│   │   ├── src/
│   │   │   ├── composables/         # useKeycloak, usePayment, usePaymentStream, useTheme
│   │   │   ├── views/               # LoginView, PaymentsView, PaymentStatusView
│   │   │   ├── components/          # AppHeader, BottomNav
│   │   │   └── api/payments.js      # Central API + SSE client
│   │   ├── nginx.conf
│   │   └── Dockerfile
│   ├── payment-gateway/             # Spring Boot 3 — edge REST API
│   │   └── src/main/java/com/showcase/gateway/
│   │       ├── config/              # SecurityConfig (JWT + Keycloak role extraction)
│   │       ├── controller/          # PaymentController (POST + SSE stream)
│   │       ├── service/             # PaymentServiceImpl, SseEmitterServiceImpl
│   │       ├── client/              # AccountVerifierClient (gRPC)
│   │       ├── messaging/           # PaymentCompletedListener (Kafka)
│   │       └── dto/                 # PaymentRequest/Response, PaymentStatusEvent
│   ├── account-verifier/            # Quarkus 3 — gRPC server + Kafka producer
│   │   └── src/main/java/com/showcase/verifier/
│   │       ├── grpc/                # AccountServiceGrpcImpl
│   │       ├── service/             # AccountVerificationService, PaymentEventPublisherImpl
│   │       ├── repository/          # AccountRepository (Panache)
│   │       └── domain/              # Account entity
│   ├── transaction-engine/          # Spring Boot 3 — Kafka consumer + MQ producer
│   │   └── src/main/java/com/showcase/engine/
│   │       ├── messaging/           # PaymentApprovedListener
│   │       ├── service/             # LedgerService, MqPublishingServiceImpl
│   │       ├── config/              # KafkaConfig (DLT + exponential backoff)
│   │       └── domain/              # TransactionLedger entity
│   └── clearing-house/              # Quarkus 3 — MQ consumer + Kafka producer
│       └── src/main/java/com/showcase/clearing/
│           ├── messaging/           # MqMessageListener, PaymentCompletedPublisherImpl
│           ├── service/             # ClearingService (clearing simulation)
│           └── config/              # MqConnectionFactoryProducer, MqProperties
│
└── infra/
    ├── docker/
    │   ├── docker-compose.yml       # Full local infrastructure stack
    │   ├── postgres-init.sql        # Creates accounts_db, ledger_db, appuser
    │   ├── config.mqsc              # IBM MQ channels, queues, dead-letter queue
    │   └── payment-realm.json       # Keycloak BankDemoRealm export
    ├── k8s/
    │   ├── infra/                   # Namespace, Keycloak, PostgreSQL, Redpanda, IBM MQ
    │   └── apps/                    # Deployments, Services, Routes for each service
    └── otel/
        └── collector-config.yaml    # Reference OTel Collector config
```

---

## Configuration Reference

### Keycloak (BankDemoRealm)

| Setting | Value |
|---|---|
| Admin console | `http://localhost:8080` · `admin / admin` |
| Realm | `BankDemoRealm` |
| Public OIDC client | `spa-payment-client` (PKCE, `S256`) |
| Bearer-only client | `backend-gateway-service` |
| Required role | `payment-init` |
| Test user | `testuser` / `password` |

### Environment variables (key ones)

| Variable | Service | Default (local) |
|---|---|---|
| `QUARKUS_DATASOURCE_JDBC_URL` | `account-verifier` | `jdbc:postgresql://localhost:5432/accounts_db` |
| `QUARKUS_DATASOURCE_USERNAME` | `account-verifier` | `appuser` |
| `SPRING_DATASOURCE_URL` | `transaction-engine` | `jdbc:postgresql://localhost:5432/ledger_db` |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | `payment-gateway` | `http://localhost:8080/realms/BankDemoRealm` |
| `GRPC_CLIENT_ACCOUNT_VERIFIER_ADDRESS` | `payment-gateway` | `static://localhost:9090` |
| `KAFKA_BOOTSTRAP_SERVERS` | Quarkus services | `localhost:9092` |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Spring Boot services | `localhost:9092` |
| `IBM_MQ_CONN_NAME` | `transaction-engine` | `localhost(1414)` |
| `IBM_MQ_HOST` | `clearing-house` | `localhost` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | all Java | `http://otel-collector:4317` |
| `OTEL_SERVICE_NAME` | all Java | *(per-service, set in Deployment)* |

### Frontend runtime (injected by Nginx at container start)

| Variable | Default |
|---|---|
| `VITE_KEYCLOAK_URL` | `http://localhost:8080` |
| `VITE_KEYCLOAK_REALM` | `BankDemoRealm` |
| `VITE_KEYCLOAK_CLIENT_ID` | `spa-payment-client` |
| `VITE_API_BASE_URL` | `http://localhost:8090` |

---

## Seed Data

PostgreSQL is pre-seeded with four test accounts via Flyway migration `V1__init_accounts.sql`:

| Account ID | Name | Balance | Status |
|---|---|---|---|
| `ACC-001` | Alice Martin | 10,000.00 | ACTIVE |
| `ACC-002` | Bob Johnson | 5,000.00 | ACTIVE |
| `ACC-003` | Charlie Brown | 0.00 | ACTIVE |
| `ACC-004` | Diana Prince | 25,000.00 | SUSPENDED |

Use `ACC-001` as the source to trigger a successful approval. Use `ACC-003` (zero balance) or `ACC-004` (suspended) to trigger a rejection.

---

## Development Notes

- **No Lombok** — all Java classes use explicit constructors, records for DTOs, and getters only where needed.
- **Constructor injection only** — no `@Autowired` or `@Inject` on fields.
- **JVM mode only** — GraalVM native compilation is intentionally out of scope. All JARs run on the standard HotSpot JVM.
- **Spring Boot and Quarkus BOMs are NOT imported in the root POM** — they are imported per child module to prevent version conflicts on shared artefacts (Jackson, gRPC, Netty).
- **`./mvnw` requires Java 21** — the wrapper downloads Maven 3.9.6 automatically on first run.

---

## End-to-End Smoke Test — Full Pipeline Verification

This section covers starting every service in the correct order and verifying that a
single payment travels the complete pipeline: HTTP → gRPC → Kafka → IBM MQ JMS → Kafka → SSE.

### Start order

Services must start in this order because `payment-gateway` connects to `account-verifier`
over gRPC on startup, and `clearing-house` connects to IBM MQ.

```
infrastructure (docker-compose) → account-verifier → transaction-engine → clearing-house → payment-gateway → spa-mobile-app
```

### 1. Build all Java services

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./mvnw clean package -DskipTests
```

### 2. Start infrastructure

```bash
docker-compose -f infra/docker/docker-compose.yml up -d
```

Wait for all healthchecks to pass (Keycloak ≈ 60 s, IBM MQ ≈ 45 s):

```bash
docker-compose -f infra/docker/docker-compose.yml ps
# Every service: "healthy" or "exited (0)" for redpanda-init
```

### 3. Start backend services (four terminals)

```bash
# Terminal 1 — account-verifier (gRPC :9090 / health :8080)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  ./mvnw -pl apps/account-verifier quarkus:dev -Ddebug=false

# Terminal 2 — transaction-engine (management :8082)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  ./mvnw -pl apps/transaction-engine spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dserver.port=8082"

# Terminal 3 — clearing-house (health :8083)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  ./mvnw -pl apps/clearing-house quarkus:dev \
  -Dquarkus.http.port=8083 -Ddebug=false

# Terminal 4 — payment-gateway (HTTP :8090)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  ./mvnw -pl apps/payment-gateway spring-boot:run \
  -Dspring-boot.run.jvmArguments="\
    -Dserver.port=8090 \
    -Dgrpc.client.account-verifier.address=static://localhost:9090 \
    -Dgrpc.client.account-verifier.negotiation-type=plaintext"
```

### 4. Start the frontend

```bash
cd apps/spa-mobile-app && npm run dev   # http://localhost:5173
```

### 5. Trigger a payment — Browser

1. Open **http://localhost:5173**, log in as **`testuser` / `password`**.
2. Submit a payment: source `ACC-001`, destination `ACC-002`, amount ≤ `10 000`.
3. The status page updates to **COMPLETED** within ≈ 5 seconds via SSE.

### 5. Trigger a payment — curl (headless)

```bash
# Obtain a Keycloak access token
TOKEN=$(curl -s -X POST \
  "http://localhost:8080/realms/BankDemoRealm/protocol/openid-connect/token" \
  -d "client_id=spa-payment-client&grant_type=password&username=testuser&password=password" \
  | jq -r .access_token)

# Initiate the payment — returns 202 with a transactionId
TXN_ID=$(curl -s -X POST http://localhost:8090/api/v1/payments \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sourceAccount":"ACC-001","destinationAccount":"ACC-002","amount":250.00,"currency":"USD"}' \
  | jq -r .transactionId)

echo "Tracking: $TXN_ID"

# Listen for the final status (blocks until COMPLETED or FAILED arrives)
curl -N http://localhost:8090/api/v1/payments/stream/$TXN_ID
```

### 6. Verify the database

```bash
# ACC-001 balance should be decremented
docker exec -it $(docker ps -qf name=postgres) \
  psql -U postgres -d accounts_db \
  -c "SELECT account_id, balance FROM accounts WHERE account_id = 'ACC-001';"

# Ledger record should exist
docker exec -it $(docker ps -qf name=postgres) \
  psql -U postgres -d ledger_db \
  -c "SELECT transaction_id, status, amount, created_at \
      FROM transaction_ledger ORDER BY created_at DESC LIMIT 3;"
```

### 7. Rejection paths

To verify error handling, use these source accounts:

| Source | Expected result | Reason |
|---|---|---|
| `ACC-001` | `COMPLETED` | Active account, sufficient balance |
| `ACC-003` | `FAILED` at gRPC step | Zero balance |
| `ACC-004` | `FAILED` at gRPC step | Account suspended |

### 8. Tear down

```bash
kill $(lsof -ti:8090,8083,8082,9090) 2>/dev/null || true
docker-compose -f infra/docker/docker-compose.yml down -v
```
