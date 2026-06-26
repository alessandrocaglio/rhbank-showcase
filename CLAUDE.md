# CLAUDE.md — AI Generation Entrypoint

> **Read this file first.** Then open the linked sub-specifications for the module you are building.
> Sub-specs are in [`docs/`](docs/). Never generate code that contradicts these documents.

---

## 1. Project Overview

This monorepo is an **observability engineering showcase** built around a simulated digital banking payment platform. The domain is secondary; the primary deliverable is demonstrating **unbroken, end-to-end distributed trace propagation** across a polyglot microservice mesh that spans HTTP, gRPC, Kafka, and JMS boundaries.

**Core objectives (non-negotiable):**

- W3C Trace Context propagated without breaks across every protocol boundary via the OTel Java Agent.
- Polyglot contrast: Spring Boot 3.x and Quarkus 3.x services coexist in the same pipeline.
- All Java services run in **JVM mode only** (no GraalVM native compilation).
- JWT identity (from Keycloak) rides alongside active spans across every service hop.
- Turn-key deployment target: Red Hat OpenShift 4.x with OpenShift Service Mesh 3 (Istio) and Grafana Tempo.

---

## 2. Service Inventory

| Service | Technology | Container Port(s) | Role in Pipeline |
|---|---|---|---|
| `spa-mobile-app` | Vue 3 + Vite, Nginx | `80` | Frontend OIDC client; initiates payments; consumes SSE |
| `payment-gateway` | Spring Boot 3.3.x, Java 21 | `8080` (HTTP) | Edge API; JWT validation; gRPC client; Kafka consumer; SSE emitter |
| `account-verifier` | Quarkus 3.15.x, Java 21 | `9090` (gRPC), `8080` (health) | Account validation; Kafka producer |
| `transaction-engine` | Spring Boot 3.3.x, Java 21 | `8080` (management) | Kafka consumer; ledger persistence; IBM MQ producer |
| `clearing-house` | Quarkus 3.15.x, Java 21 | `8080` (health) | IBM MQ consumer; Kafka producer |

---

## 3. Architecture

```text
                              [ KEYCLOAK :8080 ]
                                     │
                   (Issue JWT) │     │ (JWKS validation)
                               ▼     ▼
  ┌──────────────────────────────────────────────────────────────────────┐
  │                        spa-mobile-app (:80)                          │
  └──────┬───────────────────────────────────────────────────────▲───────┘
         │                                                       │
         │ (1) POST /api/v1/payments                             │ (8) SSE push
         │     Authorization: Bearer <JWT>                       │     /api/v1/payments/stream/{txId}
         ▼                                                       │
  ┌──────────────────────────────────────────────────────────────┴───────┐
  │                    payment-gateway (:8080)                            │
  │                       [Spring Boot 3.x]                              │
  └──────┬───────────────────────────────────────────────────────▲───────┘
         │                                                       │
         │ (2) gRPC AccountService/VerifyAccount                 │ (7) Kafka consumer
         │     trace context in gRPC metadata                    │     topic: payment-completed
         ▼                                                       │
  ┌──────────────────────────────────────────────────────────────┴───────┐
  │                   account-verifier (:9090 gRPC)                       │
  │                       [Quarkus 3.x]                                  │
  │               accounts_db (PostgreSQL + Flyway)                      │
  └──────┬───────────────────────────────────────────────────────────────┘
         │
         │ (3) Kafka producer — topic: payment-approved
         │     traceparent in Kafka record headers
         ▼
  ┌──────────────────────────────────────────────────────────────────────┐
  │                   transaction-engine (:8080)                          │
  │                       [Spring Boot 3.x]                              │
  │                ledger_db (PostgreSQL + Flyway)                       │
  └──────┬───────────────────────────────────────────────────────────────┘
         │
         │ (4) JMS producer — queue: DEV.QUEUE.CLEARING (IBM MQ)
         │     traceparent as JMS String property
         ▼
  ┌──────────────────────────────────────────────────────────────────────┐
  │                    clearing-house (:8080)                             │
  │                       [Quarkus 3.x]                                  │
  └──────┬───────────────────────────────────────────────────────────────┘
         │
         └─ (5+6) JMS consumer → simulate clearing → Kafka producer
                  topic: payment-completed
```

---

## 4. Transaction Lifecycle

| Step | Actor | Action |
|---|---|---|
| 1 | `spa-mobile-app` | Authenticates with Keycloak (OIDC Authorization Code + PKCE). Subscribes to SSE before posting. |
| 2 | `payment-gateway` | Receives POST, validates JWT, generates `txId` (UUID), returns HTTP 202 immediately. Calls `account-verifier` via gRPC. |
| 3 | `account-verifier` | Validates account balance in `accounts_db`, applies hold. Publishes to `payment-approved` Kafka topic. |
| 4 | `transaction-engine` | Consumes `payment-approved`. Writes immutable ledger record to `ledger_db`. Publishes JMS message to `DEV.QUEUE.CLEARING`. |
| 5–6 | `clearing-house` | Consumes JMS message. Simulates external clearing (SWIFT/Fedwire). Publishes to `payment-completed` Kafka topic. |
| 7 | `payment-gateway` | Consumes `payment-completed`. Resolves in-memory SSE emitter keyed on `txId`. |
| 8 | `spa-mobile-app` | Receives final payment status over SSE. |

---

## 5. Repository Structure

```
.
├── CLAUDE.md                        # ← you are here
├── docs/
│   ├── SERVICES.md                  # Per-service: deps, env vars, API contracts, DB schema, tests
│   ├── INFRASTRUCTURE.md            # Keycloak, PostgreSQL, Redpanda, IBM MQ, Docker Compose, K8s
│   ├── TRACING.md                   # OTel Agent, cross-protocol propagation, Collector config
│   └── CONVENTIONS.md               # Package layout, coding standards, testing requirements
├── pom.xml                          # Maven aggregator POM
├── mvnw / mvnw.cmd                  # Maven wrapper
├── apps/
│   ├── spa-mobile-app/              # Vue 3 + Vite frontend
│   │   ├── src/
│   │   ├── public/
│   │   ├── index.html
│   │   ├── vite.config.js
│   │   ├── package.json
│   │   ├── nginx.conf
│   │   └── Dockerfile
│   ├── grpc-api/                    # Shared proto definitions + generated Java stubs
│   │   ├── pom.xml
│   │   └── src/main/proto/
│   │       └── account.proto
│   ├── payment-gateway/             # Spring Boot 3 — edge API
│   │   ├── pom.xml
│   │   └── src/
│   ├── account-verifier/            # Quarkus 3 — gRPC server + Kafka producer
│   │   ├── pom.xml
│   │   └── src/
│   ├── transaction-engine/          # Spring Boot 3 — Kafka consumer + MQ producer
│   │   ├── pom.xml
│   │   └── src/
│   └── clearing-house/              # Quarkus 3 — MQ consumer + Kafka producer
│       ├── pom.xml
│       └── src/
└── infra/
    ├── docker/
    │   └── docker-compose.yml       # Full local dev stack
    ├── k8s/
    │   ├── infra/                   # Keycloak, PostgreSQL, Redpanda, IBM MQ K8s manifests
    │   └── apps/                    # Deployment, Service, Route per microservice
    └── otel/
        └── collector-config.yaml    # Example OTel Collector configuration (reference only)
```

---

## 6. Developer Commands

```bash
# Generate Maven wrapper (run once)
mvn wrapper:wrapper -Dmaven=3.9.6

# Build all Java modules (skip tests)
./mvnw clean package -DskipTests

# Run tests for a specific module
./mvnw test -pl apps/payment-gateway

# Build all and run tests with coverage
./mvnw clean verify

# Push container image via Jib (no Docker daemon required)
./mvnw -pl apps/payment-gateway compile jib:build -Dimage=quay.io/acaglio/payment-gateway:latest
./mvnw -pl apps/account-verifier compile jib:build -Dimage=quay.io/acaglio/account-verifier:latest
./mvnw -pl apps/transaction-engine compile jib:build -Dimage=quay.io/acaglio/transaction-engine:latest
./mvnw -pl apps/clearing-house compile jib:build -Dimage=quay.io/acaglio/clearing-house:latest

# Frontend (development)
cd apps/spa-mobile-app && npm install && npm run dev

# Frontend (container build)
docker build -t quay.io/acaglio/spa-mobile-app:latest apps/spa-mobile-app/

# Start local infrastructure
docker-compose -f infra/docker/docker-compose.yml up -d

# Stop and remove volumes
docker-compose -f infra/docker/docker-compose.yml down -v

# Deploy to OpenShift
oc apply -f infra/k8s/infra/
oc get pods -w
oc apply -f infra/k8s/apps/
```

---

## 7. Sub-Specifications Index

| Document | When to Read |
|---|---|
| [`docs/SERVICES.md`](docs/SERVICES.md) | Before generating any service code (Java or frontend) |
| [`docs/INFRASTRUCTURE.md`](docs/INFRASTRUCTURE.md) | Before generating Docker Compose, K8s manifests, or init scripts |
| [`docs/TRACING.md`](docs/TRACING.md) | Before implementing any cross-service call or OTel configuration |
| [`docs/CONVENTIONS.md`](docs/CONVENTIONS.md) | Before writing any class, test, or configuration file |
