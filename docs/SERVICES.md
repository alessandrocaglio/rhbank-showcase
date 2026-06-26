# SERVICES.md — Per-Service Specifications

> Read [`CONVENTIONS.md`](CONVENTIONS.md) alongside this file before generating service code.
> All services share the same OTel Agent injection pattern defined in [`TRACING.md`](TRACING.md).

---

## Shared Maven Parent POM (`pom.xml` at repo root)

The root `pom.xml` is a **Maven aggregator** (packaging `pom`) with the following responsibilities:

- Declares all submodules in order: `apps/grpc-api`, `apps/payment-gateway`, `apps/account-verifier`, `apps/transaction-engine`, `apps/clearing-house`.
- Imports `spring-boot-dependencies` BOM (version `3.3.5`) and `quarkus-bom` (version `3.15.1`) via `dependencyManagement`.
- Sets `<java.version>21</java.version>` and compiler source/target to `21`.
- Configures the Jib Maven plugin globally with `quay.io/acaglio` as the base registry.
- Configures JaCoCo for coverage reporting across all modules.
- Does **not** include Spring Boot or Quarkus plugin — those are in each child POM.

---

## Service A: `spa-mobile-app`

### Stack

| Item | Value |
|---|---|
| Framework | Vue 3 (Composition API) + Vite 5 |
| Styling | TailwindCSS 3 |
| Auth SDK | `keycloak-js` 25.x |
| HTTP Client | Native `fetch` API |
| SSE Client | Native `EventSource` API |
| Build output | Static files served by Nginx |
| Base image (runtime) | `nginx:1.27-alpine` |
| Build image | `node:20-alpine` (multi-stage) |

### Runtime Configuration

All environment variables are injected at **container startup** via `envsubst` in the Nginx Docker entrypoint. The Vite build uses a `config.js` file that Nginx generates from a template, making the image environment-agnostic.

| Variable | Description | Default (local) |
|---|---|---|
| `VITE_KEYCLOAK_URL` | Keycloak base URL | `http://localhost:8080` |
| `VITE_KEYCLOAK_REALM` | Keycloak realm name | `BankDemoRealm` |
| `VITE_KEYCLOAK_CLIENT_ID` | OIDC public client ID | `spa-payment-client` |
| `VITE_API_BASE_URL` | payment-gateway base URL | `http://localhost:8090` |

### Dockerfile Pattern

```dockerfile
# Stage 1: build
FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# Stage 2: serve
FROM nginx:1.27-alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY docker-entrypoint.sh /docker-entrypoint.d/40-env-config.sh
RUN chmod +x /docker-entrypoint.d/40-env-config.sh
EXPOSE 80
```

The `docker-entrypoint.sh` script generates a `public/config.js` from env vars at startup using `envsubst`. Vue reads `window.__APP_CONFIG__` at runtime instead of Vite's compile-time `import.meta.env`.

### Key `nginx.conf` rules

- All routes return `index.html` (`try_files $uri $uri/ /index.html`) for client-side routing.
- Proxy `/api/` to `payment-gateway` if the gateway is co-located (optional, for same-origin deployment).

### Application Pages

| Route | Purpose |
|---|---|
| `/` | Login / redirect to Keycloak |
| `/payments` | Payment initiation form |
| `/payments/:txId/status` | Real-time status (SSE-driven) |

### OIDC Flow

Use `keycloak-js` in `check-sso` mode on app mount. On successful auth, store the token in memory (not localStorage). Attach `Authorization: Bearer <token>` to every `fetch` call. Use Keycloak's `onTokenExpired` callback to refresh silently.

### Testing

Frontend unit tests use **Vitest** + **@testing-library/vue**. Coverage target: ≥ 80% of component logic.

---

## Service B: `payment-gateway`

### Stack

| Item | Value |
|---|---|
| Framework | Spring Boot 3.3.5 |
| Java | 21 (JVM) |
| Build | Maven, Jib for image |

### Maven Dependencies (`apps/payment-gateway/pom.xml`)

```xml
<!-- Web & Security -->
<dependency>spring-boot-starter-web</dependency>
<dependency>spring-boot-starter-oauth2-resource-server</dependency>
<dependency>spring-boot-starter-validation</dependency>
<dependency>spring-boot-starter-actuator</dependency>

<!-- gRPC client -->
<dependency>net.devh:grpc-client-spring-boot-starter:3.1.0.RELEASE</dependency>
<dependency>apps/grpc-api (local module)</dependency>

<!-- Kafka -->
<dependency>spring-kafka</dependency>

<!-- OTel SDK (manual span operations only) -->
<dependency>io.opentelemetry:opentelemetry-api</dependency>
```

### Environment Variables

| Variable | Description | Example |
|---|---|---|
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | Keycloak realm issuer URL | `http://keycloak:8080/realms/BankDemoRealm` |
| `GRPC_CLIENT_ACCOUNT_VERIFIER_ADDRESS` | gRPC target (net.devh format) | `static://account-verifier:9090` |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Kafka/Redpanda bootstrap | `redpanda:9092` |
| `SPRING_KAFKA_CONSUMER_GROUP_ID` | Consumer group | `payment-gateway-group` |
| `APP_KAFKA_TOPICS_PAYMENT_COMPLETED` | Topic to consume final status from | `payment-completed` |
| `APP_KAFKA_TOPICS_PAYMENT_COMPLETED_DLT` | Dead-letter topic for failed consumption | `payment-completed.DLT` |
| `SERVER_PORT` | HTTP port | `8080` |

### Inbound API

**`POST /api/v1/payments`** — Requires `Authorization: Bearer <JWT>` with role `payment-init`.

Request body:
```json
{
  "sourceAccount": "ACC-001",
  "destinationAccount": "ACC-002",
  "amount": 150.00,
  "currency": "USD"
}
```

Response `202 Accepted`:
```json
{
  "transactionId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "PENDING",
  "message": "Payment accepted for processing"
}
```

**`GET /api/v1/payments/stream/{transactionId}`** — SSE endpoint. Returns `text/event-stream`. The client subscribes **before** posting the payment. Emitter is stored in a `ConcurrentHashMap<String, SseEmitter>` keyed on `transactionId`. Timeout: 300 seconds. On completion event, the emitter is removed from the map.

SSE event payload:
```json
{
  "transactionId": "550e8400-...",
  "status": "COMPLETED|FAILED",
  "timestamp": "2024-01-15T10:30:00Z",
  "detail": "Payment settled successfully"
}
```

### Outbound: gRPC

Calls `AccountService/VerifyAccount` on `account-verifier`. The OTel Agent injects the active trace context into gRPC metadata automatically. The call is **synchronous** — the gateway awaits the gRPC response before returning HTTP 202. If gRPC returns an error, return HTTP 422 with error detail.

### Outbound: Kafka Consumer

Listens on topic bound to `${app.kafka.topics.payment-completed}` (env: `APP_KAFKA_TOPICS_PAYMENT_COMPLETED`, default `payment-completed`), group `payment-gateway-group`. On message receipt, looks up the `SseEmitter` by `transactionId`, emits the event, and completes the emitter. Configure a `DeadLetterPublishingRecoverer` to route unparseable messages to the DLT topic (`${app.kafka.topics.payment-completed-dlt}`).

### Security

- Spring Security `SecurityFilterChain` configured as stateless JWT resource server.
- `/api/v1/payments/stream/**` requires authentication.
- `/actuator/health` is permitted without auth.
- Validate the `payment-init` role from the JWT claims.

### Testing

- Unit test the `PaymentController` with `@WebMvcTest`, mock the gRPC stub and Kafka template.
- Unit test `SseEmitterService` for emitter lifecycle (register, resolve, timeout eviction).
- Unit test the Kafka consumer with a mocked `SseEmitter`.
- Coverage target: ≥ 80%.

---

## Service C: `account-verifier`

### Stack

| Item | Value |
|---|---|
| Framework | Quarkus 3.15.1 |
| Java | 21 (JVM) |
| Build | Maven, Jib for image |

### Quarkus Extensions (`apps/account-verifier/pom.xml`)

```
quarkus-grpc
quarkus-hibernate-orm-panache
quarkus-jdbc-postgresql
quarkus-flyway
quarkus-smallrye-reactive-messaging-kafka
quarkus-opentelemetry
quarkus-smallrye-health
```

Also depends on `apps/grpc-api` (local module) for the generated gRPC service interface.

### Environment Variables

| Variable | Description | Example |
|---|---|---|
| `QUARKUS_DATASOURCE_JDBC_URL` | JDBC URL for `accounts_db` | `jdbc:postgresql://postgres:5432/accounts_db` |
| `QUARKUS_DATASOURCE_USERNAME` | DB username | `appuser` |
| `QUARKUS_DATASOURCE_PASSWORD` | DB password | `apppassword` |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka/Redpanda bootstrap | `redpanda:9092` |
| `KAFKA_TOPIC_PAYMENT_APPROVED` | Topic to publish approved payments | `payment-approved` |
| `KAFKA_TOPIC_PAYMENT_APPROVED_DLT` | Dead-letter topic | `payment-approved.DLT` |
| `QUARKUS_GRPC_SERVER_PORT` | gRPC listen port | `9090` |
| `QUARKUS_HTTP_PORT` | Health/management HTTP port | `8080` |

### Inbound: gRPC Server

Implements `AccountService` defined in `grpc-api`. The single RPC:

```protobuf
service AccountService {
  rpc VerifyAccount (VerifyAccountRequest) returns (VerifyAccountResponse);
}

message VerifyAccountRequest {
  string transaction_id  = 1;
  string source_account  = 2;
  string destination_account = 3;
  double amount          = 4;
  string currency        = 5;
}

message VerifyAccountResponse {
  bool   approved        = 1;
  string reason          = 2;
}
```

Business logic: query `accounts` table, verify balance ≥ amount, verify account `status = 'ACTIVE'`. If approved, decrement balance and set a hold flag in the DB within the same transaction. Then emit a Kafka message.

### Database: `accounts_db`

**Flyway migration** at `src/main/resources/db/migration/V1__init_accounts.sql`:

```sql
CREATE TABLE accounts (
    account_id       VARCHAR(50)     PRIMARY KEY,
    customer_name    VARCHAR(100)    NOT NULL,
    balance          NUMERIC(15, 2)  NOT NULL,
    status           VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE'
);

INSERT INTO accounts (account_id, customer_name, balance, status) VALUES
    ('ACC-001', 'Alice Martin',  10000.00, 'ACTIVE'),
    ('ACC-002', 'Bob Johnson',    5000.00, 'ACTIVE'),
    ('ACC-003', 'Charlie Brown',     0.00, 'ACTIVE'),
    ('ACC-004', 'Diana Prince',  25000.00, 'SUSPENDED');
```

### Outbound: Kafka Producer

Publishes to topic bound to `${KAFKA_TOPIC_PAYMENT_APPROVED:payment-approved}` via SmallRye Reactive Messaging channel `payment-approved`. The OTel Agent intercepts the Kafka producer client and injects `traceparent` into Kafka record headers automatically.

`application.properties` mapping:
```properties
mp.messaging.outgoing.payment-approved.topic=${KAFKA_TOPIC_PAYMENT_APPROVED:payment-approved}
mp.messaging.outgoing.payment-approved-dead-letter.topic=${KAFKA_TOPIC_PAYMENT_APPROVED_DLT:payment-approved.DLT}
```

Kafka message payload:
```json
{
  "transactionId": "550e8400-...",
  "sourceAccount": "ACC-001",
  "destinationAccount": "ACC-002",
  "amount": 150.00,
  "currency": "USD",
  "approvedAt": "2024-01-15T10:30:00Z"
}
```

Configure a dead-letter channel: failed messages route to the DLT topic (`${KAFKA_TOPIC_PAYMENT_APPROVED_DLT}`).

### Testing

- Use `@QuarkusTest` with `@InjectMock` for the Panache repository.
- Test the gRPC service implementation with `QuarkusGrpcTest`.
- Test Kafka producer with `@QuarkusIntegration` (DevServices) in integration tests (unit phase: mock the channel).
- Coverage target: ≥ 80%.

---

## Service D: `transaction-engine`

### Stack

| Item | Value |
|---|---|
| Framework | Spring Boot 3.3.5 |
| Java | 21 (JVM) |
| Build | Maven, Jib for image |

### Maven Dependencies (`apps/transaction-engine/pom.xml`)

```xml
<dependency>spring-boot-starter-data-jpa</dependency>
<dependency>spring-boot-starter-actuator</dependency>
<dependency>spring-kafka</dependency>
<dependency>com.ibm.mq:mq-jms-spring-boot-starter:3.4.2</dependency>
<dependency>org.flywaydb:flyway-core</dependency>
<dependency>org.postgresql:postgresql</dependency>
<dependency>io.opentelemetry:opentelemetry-api</dependency>
```

### Environment Variables

| Variable | Description | Example |
|---|---|---|
| `SPRING_DATASOURCE_URL` | JDBC URL for `ledger_db` | `jdbc:postgresql://postgres:5432/ledger_db` |
| `SPRING_DATASOURCE_USERNAME` | DB username | `appuser` |
| `SPRING_DATASOURCE_PASSWORD` | DB password | `apppassword` |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Kafka/Redpanda bootstrap | `redpanda:9092` |
| `SPRING_KAFKA_CONSUMER_GROUP_ID` | Consumer group | `transaction-engine-group` |
| `APP_KAFKA_TOPICS_PAYMENT_APPROVED` | Topic to consume approved payments from | `payment-approved` |
| `APP_KAFKA_TOPICS_PAYMENT_APPROVED_DLT` | Dead-letter topic for failed consumption | `payment-approved.DLT` |
| `IBM_MQ_CONN_NAME` | IBM MQ connection (host(port)) | `ibmmq(1414)` |
| `IBM_MQ_QUEUE_MANAGER` | Queue manager name | `QM1` |
| `IBM_MQ_CHANNEL` | MQ channel name | `DEV.APP.SVRCONN` |
| `IBM_MQ_USER` | MQ username (optional) | _(empty for dev)_ |
| `IBM_MQ_PASSWORD` | MQ password (optional) | _(empty for dev)_ |
| `APP_MQ_QUEUES_CLEARING` | IBM MQ destination queue for clearing | `DEV.QUEUE.CLEARING` |
| `APP_MQ_QUEUES_DEAD_LETTER` | IBM MQ dead-letter queue | `DEV.DEAD.LETTER.QUEUE` |
| `SERVER_PORT` | Management HTTP port | `8080` |

### Inbound: Kafka Consumer

Listens on topic bound to `${app.kafka.topics.payment-approved}` (env: `APP_KAFKA_TOPICS_PAYMENT_APPROVED`, default `payment-approved`), group `transaction-engine-group`. On each message:
1. Deserialize the JSON payload.
2. Persist a `TransactionLedger` entity to `ledger_db`.
3. Publish a JMS message to the configurable clearing queue (env: `APP_MQ_QUEUES_CLEARING`, default `DEV.QUEUE.CLEARING`).

Configure `DeadLetterPublishingRecoverer` to route failures to `${app.kafka.topics.payment-approved-dlt}` after 3 retries with exponential backoff.

### Database: `ledger_db`

**Flyway migration** at `src/main/resources/db/migration/V1__init_ledger.sql`:

```sql
CREATE TABLE transaction_ledger (
    transaction_id       VARCHAR(50)     PRIMARY KEY,
    source_account       VARCHAR(50)     NOT NULL,
    destination_account  VARCHAR(50)     NOT NULL,
    amount               NUMERIC(15, 2)  NOT NULL,
    currency             VARCHAR(10)     NOT NULL DEFAULT 'USD',
    status               VARCHAR(20)     NOT NULL,
    created_at           TIMESTAMP       NOT NULL DEFAULT NOW()
);
```

### Outbound: IBM MQ JMS Producer

Use `JmsTemplate` (auto-configured by `mq-jms-spring-boot-starter`) to send messages to the queue bound to `${app.mq.queues.clearing}` (env: `APP_MQ_QUEUES_CLEARING`, default `DEV.QUEUE.CLEARING`). The queue name must be injected via `@Value("${app.mq.queues.clearing}")` — never hardcoded as a constant.

**Mandatory:** Explicitly set the `traceparent` JMS String property as a safety net for trace propagation across the JMS boundary (see [`TRACING.md`](TRACING.md) for details):

```java
// clearingQueue is injected via @Value("${app.mq.queues.clearing}")
jmsTemplate.send(clearingQueue, session -> {
    TextMessage message = session.createTextMessage(payload);
    message.setStringProperty("traceparent", currentTraceparent);
    message.setStringProperty("transactionId", transactionId);
    return message;
});
```

### Testing

- Unit test the Kafka listener class with `@EmbeddedKafka` (Spring Kafka test support).
- Unit test the JPA repository with `@DataJpaTest` and an embedded H2 database (schema-compatible).
- Unit test the JMS publishing logic with Mockito-mocked `JmsTemplate`.
- Coverage target: ≥ 80%.

---

## Service E: `clearing-house`

### Stack

| Item | Value |
|---|---|
| Framework | Quarkus 3.15.1 |
| Java | 21 (JVM) |
| Build | Maven, Jib for image |

### Quarkus Extensions + Dependencies (`apps/clearing-house/pom.xml`)

```
quarkus-smallrye-reactive-messaging-kafka
quarkus-opentelemetry
quarkus-smallrye-health
quarkus-arc
```

IBM MQ JMS is **not** available as a native Quarkus extension. Use the IBM MQ JMS client library directly with a CDI producer bean:

```xml
<dependency>
    <groupId>com.ibm.mq</groupId>
    <artifactId>com.ibm.mq.allclient</artifactId>
    <version>9.3.5.1</version>
</dependency>
<dependency>
    <groupId>javax.jms</groupId>
    <artifactId>javax.jms-api</artifactId>
    <version>2.0.1</version>
</dependency>
```

Create a `@ApplicationScoped` CDI bean `MqConnectionFactoryProducer` that creates and exposes an `MQConnectionFactory` configured from environment variables. Use a dedicated `@ApplicationScoped` JMS listener bean that starts a `MessageConsumer` in a background thread on `@PostConstruct`.

### Environment Variables

| Variable | Description | Example |
|---|---|---|
| `IBM_MQ_HOST` | IBM MQ hostname | `ibmmq` |
| `IBM_MQ_PORT` | IBM MQ listener port | `1414` |
| `IBM_MQ_QUEUE_MANAGER` | Queue manager name | `QM1` |
| `IBM_MQ_CHANNEL` | MQ channel name | `DEV.APP.SVRCONN` |
| `IBM_MQ_QUEUE_NAME` | JMS input queue name | `DEV.QUEUE.CLEARING` |
| `IBM_MQ_USER` | MQ username (optional) | _(empty for dev)_ |
| `IBM_MQ_PASSWORD` | MQ password (optional) | _(empty for dev)_ |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka/Redpanda bootstrap | `redpanda:9092` |
| `KAFKA_TOPIC_PAYMENT_COMPLETED` | Topic to publish clearing results | `payment-completed` |
| `KAFKA_TOPIC_PAYMENT_COMPLETED_DLT` | Dead-letter topic | `payment-completed.DLT` |
| `QUARKUS_HTTP_PORT` | Health HTTP port | `8080` |

### Inbound: IBM MQ JMS Consumer

The `@ApplicationScoped` listener bean:
1. Creates a `JMSContext` from the CDI-produced `MQConnectionFactory`.
2. Starts a message listener on the queue bound to `${clearing.mq.queue-name}` (env: `IBM_MQ_QUEUE_NAME`, default `DEV.QUEUE.CLEARING`).
3. On each message, reads the `traceparent` JMS String property and restores the trace context (see [`TRACING.md`](TRACING.md)).
4. Simulates clearing processing (random delay 100–500ms, 95% success rate).
5. Publishes a result event to the configurable `payment-completed` topic.

### Outbound: Kafka Producer

Publishes to topic bound to `${KAFKA_TOPIC_PAYMENT_COMPLETED:payment-completed}` via SmallRye Reactive Messaging channel `payment-completed`.

`application.properties` mapping:
```properties
mp.messaging.outgoing.payment-completed.topic=${KAFKA_TOPIC_PAYMENT_COMPLETED:payment-completed}
mp.messaging.outgoing.payment-completed-dead-letter.topic=${KAFKA_TOPIC_PAYMENT_COMPLETED_DLT:payment-completed.DLT}
```

Kafka message payload:
```json
{
  "transactionId": "550e8400-...",
  "status": "COMPLETED",
  "clearedAt": "2024-01-15T10:30:01Z",
  "detail": "Clearing successful"
}
```

On clearing failure (simulated), publish with `"status": "FAILED"`. Failed Kafka emissions go to `payment-completed.DLT`.

### Testing

- Unit test the clearing service logic with Mockito (mock the JMS `TextMessage` and the Kafka emitter).
- Unit test the `MqConnectionFactoryProducer` with Mockito.
- Coverage target: ≥ 80%.

---

## Shared: `grpc-api` Module

**Location:** `apps/grpc-api/`

This Maven module contains the Protobuf definition and generates Java stubs. Both `payment-gateway` (client) and `account-verifier` (server) declare it as a dependency.

### `pom.xml` key configuration

```xml
<packaging>jar</packaging>
<!-- protobuf-maven-plugin generates Java sources from .proto files -->
<!-- grpc-java plugin generates gRPC service stubs -->
<!-- Dependencies: io.grpc:grpc-protobuf, io.grpc:grpc-stub, com.google.protobuf:protobuf-java -->
```

### Proto File: `src/main/proto/account.proto`

```protobuf
syntax = "proto3";
package com.showcase.grpc.account;
option java_package = "com.showcase.grpc.account";
option java_multiple_files = true;

service AccountService {
  rpc VerifyAccount (VerifyAccountRequest) returns (VerifyAccountResponse);
}

message VerifyAccountRequest {
  string transaction_id      = 1;
  string source_account      = 2;
  string destination_account = 3;
  double amount              = 4;
  string currency            = 5;
}

message VerifyAccountResponse {
  bool   approved = 1;
  string reason   = 2;
}
```
