# Code Review Findings
_Six automated reviews (one per module + infra), curated and graded._

**Grading:**
- 🔴 **Bug / Breaks the demo** — incorrect behaviour, data loss, or will crash
- 🟠 **Real gap** — not an intentional simplification; should be fixed for correctness or security
- 🟡 **Showcase blind-spot** — undermines the stated observability/tracing goal of the project
- ⚪ **Noted** — intentional simplification, but worth documenting or acknowledging in the README

---

## 1. Cross-cutting: Money Amounts as `double`

🔴 **account.proto declares `amount` as `double`; all services propagate this.**

IEEE 754 `double` cannot represent many decimal values exactly (`0.1 + 0.2 ≠ 0.3`). The conversion in `AccountServiceGrpcImpl.java:43` (`new BigDecimal(String.valueOf(request.getAmount()))`) partially compensates but the precision loss already occurred when the proto was serialised on the wire. `PaymentServiceImpl.java:37` in `payment-gateway` further does `request.amount().doubleValue()` before sending.

**Fix:** Change `account.proto` field `amount` to `string` (or use `google.type.Money`). Parse to `BigDecimal` at each service boundary.

---

## 2. account-verifier

### 2.1 🔴 Negative amount credited to account balance
`AccountVerificationService.java` — no guard against `amount ≤ 0`.
- `balance.compareTo(negativeAmount) < 0` is always **false** (any balance is > a negative number) → the verification is approved.
- `UPDATE balance = balance - (-50)` **adds** money to the account.

A trivial call with `amount = -100` silently credits the source account.

### 2.2 🔴 TOCTOU race on concurrent payments from the same account
`AccountVerificationService.java:47-60` — the flow is: `findByIdOptional` (read balance), check, then `update("balance = balance - ?1 ...")`. No `SELECT FOR UPDATE` / `@Version` optimistic lock. Two concurrent requests for the same account, both with amounts that individually pass the balance check, will both deduct — resulting in a negative balance. The DB schema has no `CHECK (balance >= 0)` to catch this at the last line of defence.

### 2.3 🔴 Dual-write: DB debit commits even if Kafka publish fails
`AccountVerificationService.java:55-70` + `PaymentEventPublisherImpl.java:28-35`:
- `publishApproved()` is `@Transactional(TxType.NOT_SUPPORTED)` — this **suspends** the outer JTA transaction before `emitter.send()` is called.
- `emitter.send()` returns a `CompletionStage<Void>` — its return value is ignored; asynchronous Kafka failures (broker down, buffer overflow) are silently discarded.
- **Result:** account balance is debited and committed to DB, Kafka message is never delivered, pipeline stalls with no alert. Money deducted, payment never processed.

This is the most critical correctness flaw in the codebase.

### 2.4 🟠 Internal exception messages leaked to gRPC callers
`AccountServiceGrpcImpl.java:55`: `Status.INTERNAL.withDescription(ar.cause().getMessage())` forwards raw exception messages (which may contain SQL error text, table names, or stack detail) to the calling `payment-gateway`. Should be a generic message; detail stays in traces/logs.

### 2.5 🟠 Destination account never validated
`AccountVerificationService.java` — `destinationAccount` is passed to the service but never looked up. A payment to a non-existent account is approved and the balance deducted. The clearing house will later publish `payment-completed` for an account that doesn't exist.

### 2.6 🟡 No idempotency guard
`transactionId` is accepted and used for span attributes but never stored. A gRPC retry (from payment-gateway timeout or network blip) will deduct the balance a second time and publish a second Kafka event for the same `txId`. No unique constraint on `transactionId` in `V1__init_accounts.sql`.

---

## 3. transaction-engine

### 3.1 🔴 MDC leak across Kafka consumer threads
`LedgerService.java:22`: `MDC.put("transactionId", ...)` is called with no corresponding `MDC.remove()` in a `finally` block. Spring Kafka reuses consumer threads from a pool — MDC state from payment N bleeds into payment N+1 on the same thread, corrupting log correlation. Every log line processed after the first payment on a given thread carries the wrong `transactionId`.

### 3.2 🔴 Kafka redelivery causes primary-key constraint violation (no idempotency)
`PaymentApprovedListener.java:42-44` — if Kafka redelivers `payment-approved` (consumer restart, rebalance), `repository.save(ledger)` attempts a duplicate `INSERT` with the same `transactionId` PK → `DataIntegrityViolationException`. This triggers the `DefaultErrorHandler` retry loop (three more failures), then routes to the DLT. The clearing message is **never sent** for the redelivered event — the payment is permanently stuck.

**Fix:** `existsById(transactionId)` check before `save()` + conditional MQ publish.

### 3.3 🔴 Dual-write: ledger committed before MQ publish, no rollback on MQ failure
`PaymentApprovedListener.java:43-44`:
```java
TransactionLedger savedLedger = ledgerService.persistLedgerRecord(event);   // commits
mqPublishingService.publishToClearingQueue(savedLedger);                     // outside tx
```
If the IBM MQ send fails, the ledger record is permanently committed as `PENDING` but no clearing message ever reaches the queue. The payment is stuck with no recovery path.

### 3.4 🟠 TIMESTAMP without time zone in ledger schema
`V1__init_ledger.sql:7`: `created_at TIMESTAMP`. PostgreSQL `TIMESTAMP` stores no timezone offset. `LocalDateTime.now()` in the entity captures JVM local time. Container JVM and DB may have different timezones → ambiguous or incorrect timestamps in the immutable ledger. Should be `TIMESTAMP WITH TIME ZONE` + `Instant`/`OffsetDateTime`.

### 3.5 🟠 Ledger status permanently "PENDING" — never updated to CLEARED/FAILED
The `transaction_ledger` table's `status` column is written as `"PENDING"` and never updated. There is no mechanism for the downstream result to flow back. Any query on the ledger DB will show every payment as perpetually pending, which is misleading and means the ledger serves no useful audit function.

### 3.6 🟡 `traceId` not stored in the ledger record
The showcase's primary goal is demonstrating end-to-end trace propagation. The `transaction_ledger` table has no `trace_id` column. You cannot join a Grafana Tempo trace to a specific DB row without relying solely on the span's attribute. Adding `trace_id VARCHAR(32)` to the schema and populating it from `Span.current().getSpanContext().getTraceId()` would make a compelling observability demonstration.

### 3.7 ⚪ DLT topic `payment-approved.DLT` configured in application.yml but never used
`app.kafka.topics.payment-approved-dlt` is dead configuration. `KafkaConfig.java` builds the DLT name from `record.topic() + ".DLT"` at runtime instead. If these drift, the DLT silently routes to a different topic.

---

## 4. clearing-house

### 4.1 🔴 AUTO_ACKNOWLEDGE mode causes message loss if processing fails
`MqMessageListener.listenLoop():73` — `connectionFactory.createContext()` with no mode argument defaults to `AUTO_ACKNOWLEDGE`. The JMS spec acknowledges the message to the broker **as soon as `consumer.receive()` returns** — before `processMessage()` is called. If processing fails (Kafka publish throws, JVM crashes), the message has already been removed from the queue. It cannot be redelivered.

**Fix:** Use `Session.CLIENT_ACKNOWLEDGE` and call `message.acknowledge()` only after a successful Kafka publish.

### 4.2 🔴 Kafka publish failure permanently kills the listener thread
`MqMessageListener.listenLoop()` — a `RuntimeException` from `publisher.publish()` (e.g., Kafka back-pressure overflow) is not a `JMSRuntimeException`. The outer catch at line 88 only catches `JMSRuntimeException`. A bare `RuntimeException` propagates out of the inner `while` loop uncaught and terminates the listener thread entirely. **One Kafka broker blip → listener dies, all subsequent MQ messages are unprocessed until the pod restarts.** There is no self-recovery, no alert, and no liveness probe that detects this condition.

### 4.3 🟠 `consumer.receive()` with no timeout — stale connections hang indefinitely
`listenLoop():78` — `consumer.receive()` has no timeout argument. A silent network partition that doesn't raise an exception immediately leaves the thread blocked forever with no way to detect staleness. Use `consumer.receive(heartbeatMillis)` and poll `isInterrupted()`.

### 4.4 🟠 Non-TextMessage types silently dropped
`listenLoop():79` — `if (message instanceof TextMessage)` — any `BytesMessage` or `MapMessage` inadvertently enqueued is AUTO_ACKNOWLEDGE'd (already consumed from MQ) and silently discarded. No log, no metric, no DLQ. The message is gone.

### 4.5 🟠 No `@PreDestroy` — listener thread killed mid-message on shutdown, span never ended
No shutdown hook exists. When Quarkus shuts down and tears down CDI, the daemon thread is killed abruptly. Any in-flight span started in `processMessage()` at line 115 will never reach `span.end()` (line 133 `finally` never executes), leaking un-ended spans in the OTel exporter buffer.

### 4.6 🟡 Dead DLT Kafka channel
`application.properties` declares `payment-completed-dead-letter` as an outgoing Kafka channel but no `@Channel("payment-completed-dead-letter")` injection exists anywhere in the codebase. The DLT configuration is completely inert — failures have no dead-letter path.

---

## 5. payment-gateway

### 5.1 🔴 No gRPC call deadline — thread pool exhaustion under load
`AccountVerifierClient.java:20` — `stub.verifyAccount(request)` has no deadline. If `account-verifier` is slow or unresponsive, the HTTP thread blocks indefinitely. Under concurrent load this exhausts the Tomcat thread pool and the gateway stops serving all requests. Fix: `stub.withDeadlineAfter(5, TimeUnit.SECONDS).verifyAccount(...)`.

### 5.2 🟠 `SseEmitter` TOCTOU race between timeout and Kafka event delivery
`SseEmitterServiceImpl.java:52-58` — `emitter.onTimeout()` removes the `CompletableFuture` from `pending` via `computeIfPresent`, but `whenComplete()` fires concurrently if the Kafka event arrives at the same instant. `SseEmitter.send()` and `SseEmitter.complete()` are not thread-safe for concurrent calls — a concurrent `onTimeout` + `send` can produce undefined behaviour.

### 5.3 🟠 CORS origin hardcoded to `http://localhost:3000`
`SecurityConfig.java:47` — for the OpenShift target this will silently block all browser → API calls. Should be `${CORS_ALLOWED_ORIGINS:http://localhost:3000}` from `application.yml`.

### 5.4 🟠 JWT subject not forwarded to gRPC downstream
`PaymentServiceImpl` — the `Authorization: Bearer <token>` from the original HTTP request is not extracted and attached to the outgoing gRPC metadata. `account-verifier` has no way to audit who initiated the payment. For a showcase whose tagline is "JWT identity rides alongside active spans across every service hop" (CLAUDE.md), this is a stated non-negotiable that isn't implemented.

### 5.5 🟡 Missing span attributes on SSE endpoint and Kafka consumer
- `PaymentController.streamPaymentStatus()` — no span attributes (txId, subscriber identity) on the SSE subscription span.
- `PaymentCompletedListener.java` — `event.transactionId()` is available after deserialisation but never added as `bank.payment.transaction_id` to the span. This breaks trace correlation in Grafana Tempo between the Kafka consumer span and the payment lifecycle.

### 5.6 🟡 No MDC for `transactionId` in application log lines
Neither `PaymentCompletedListener` nor `PaymentController` inject `transactionId` into MDC. Grep-based log correlation across a payment's lifecycle requires using `trace_id` only (injected automatically by OTel agent), which is harder to use operationally.

### 5.7 ⚪ `CompletableFuture` in `SseEmitterServiceImpl` leaks if no client ever connects
When `resolve()` fires before `register()` (pipeline completes before browser subscribes), the entry stays in `pending` forever if no client ever calls `/stream/{txId}` (browser crash, network drop). Low risk at demo scale; real production needs a TTL eviction.

### 5.8 ⚪ No idempotency key on `POST /api/v1/payments`
A browser timeout + retry creates a duplicate payment. Real payment APIs accept a client-provided idempotency key.

---

## 6. spa-mobile-app

### 6.1 🔴 Token not refreshed before API call
`src/api/payments.js:6` — `getToken()` returns `keycloak.token` with no expiry check. If the token is within the 30-second `onTokenExpired` window but `updateToken()` has not yet resolved, `POST /api/v1/payments` goes out with an expired token → 401. No retry logic exists in `initiatePayment`. Fix: `await keycloak.updateToken(30)` before constructing the request.

### 6.2 🔴 SSE listener thread killed permanently by non-JMS exception (SPA symptom)
On the backend, the clearing-house listener dies on Kafka failure (see 4.2). From the SPA's perspective: the EventSource stays open, retries exhaust, `CONNECTION_LOST` is shown — but the root cause is invisible to the user and the backend doesn't recover without a pod restart.

### 6.3 🔴 SSE EventSource not closed after terminal status received
`src/composables/usePaymentStream.js` — when `status: 'COMPLETED'` or `status: 'FAILED'` arrives, the refs are updated but the `EventSource` is left open. The server closes its end, triggering the browser's built-in SSE reconnect (distinct from the app's own `onerror` retry). This causes unexpected reconnection attempts after a payment completes.

Fix: call `cleanup()` inside `onmessage` when a terminal status is received.

### 6.4 🟠 `usePaymentStream` has a module-level side effect (starts connection on import)
`src/composables/usePaymentStream.js:55` — `connect()` is called inside the composable body at evaluation time, before the composable is exported. This violates the composable contract (side effects only when explicitly invoked) and makes the module difficult to test in isolation.

### 6.5 🟠 `payment.type` field (INSTANT/STANDARD) tracked in form but silently dropped from API payload
`src/composables/usePayment.js:49-54` — the radio group state is never sent in `initiatePayment`. The user sees a choice that has no effect. Either wire it up or remove the form field.

### 6.6 🟠 `v-html` used to inject SVG strings from JavaScript arrays
`src/components/BottomNav.vue` — SVG icon strings stored in JS arrays are injected via `v-html`. Content is static today so XSS risk is theoretical, but the pattern fails Vue linting rules and becomes dangerous if icons are ever loaded from a dynamic source. Should be extracted into small icon components.

### 6.7 🟡 No 404 / catch-all route
`src/router/index.js` has no `{ path: '/:pathMatch(.*)*' }` entry. Navigating to any unknown path renders a blank `<main>` with no feedback.

### 6.8 🟡 Already-authenticated users not redirected away from `/login`
Router has a guard blocking unauthenticated users from protected routes, but no symmetric guard redirecting authenticated users from `/login` to `/dashboard`.

### 6.9 ⚪ Colour contrast fails WCAG AA for small text
`#ee0000` on `#ffffff` ≈ 4.0:1 — meets AA for large text (18pt+) but fails for `.field-error` (0.75rem) and `.api-error` (0.85rem). Swap to `#c20000` (≈ 5.2:1) for error text to pass AA at all sizes.

---

## 7. Infrastructure

### 7.1 🔴 No OTel Collector deployed in Kubernetes — tracing showcase is broken on OpenShift
`infra/k8s/otel/collector-config.yaml` exists but there is no `Deployment`, `Service`, `ConfigMap`, or `OpenTelemetryCollector` CR for it anywhere under `infra/k8s/`. All app deployments export to `http://otel-collector:4317` which resolves to nothing. Distributed tracing — **the primary deliverable of this showcase** — cannot function on OpenShift as-is.

### 7.2 🔴 No Grafana / Tempo deployed in Kubernetes
The collector config references `tempo:4317` as the trace backend but no Tempo or Grafana deployment exists in any manifest. Traces have nowhere to go and nothing to view them with. Both need Operator-based deployment (Tempo Operator + Grafana Operator) or Helm charts.

### 7.3 🔴 Nginx runs as root — will be blocked by OpenShift default SCC
`apps/spa-mobile-app/Dockerfile` uses `nginx:1.27-alpine` whose master process runs as root. OpenShift's `restricted` SCC (the default) forbids root containers. The SPA pod will `CrashLoopBackOff` on a default OpenShift cluster. Replace with `nginxinc/nginx-unprivileged` (runs on port 8080 as uid 101).

### 7.4 🔴 `spa-mobile-app` K8s deployment contains literal `<cluster-domain>` placeholders
`infra/k8s/apps/spa-mobile-app/deployment.yaml:24,30` — env vars include `https://keycloak-showcase.apps.<cluster-domain>`. This deployment cannot be applied as-is; it requires manual string substitution. No Kustomize overlay or Helm chart exists to handle this.

### 7.5 🔴 `payment-gateway` K8s issuer URI causes JWT validation failure
`infra/k8s/apps/payment-gateway/deployment.yaml:40` — `ISSUER_URI: http://keycloak:8080/realms/BankDemoRealm`. Tokens issued via the OpenShift Route carry `iss: https://keycloak-showcase.apps.<cluster>/realms/BankDemoRealm`. Spring Security will reject every token with an issuer mismatch. The fix used in Docker Compose (`JWK_SET_URI` + `ISSUER_URI: http://localhost:8080/...`) is absent from the K8s manifests.

### 7.6 🔴 No `ServiceMeshMemberRoll` — Istio sidecar injection won't activate on OpenShift
The `showcase` namespace is labelled `istio-injection: enabled` (`infra/k8s/infra/namespace.yaml:6`) but OpenShift Service Mesh 3 requires the namespace to be listed in a `ServiceMeshMemberRoll` resource under the control plane namespace. Without this CR, the label is ignored, no sidecars are injected, and the entire service mesh layer of the demo does not function.

### 7.7 🟠 `directAccessGrantsEnabled` is `true` in Docker realm, `false` in K8s ConfigMap
`infra/docker/payment-realm.json:32` vs `infra/k8s/infra/keycloak/configmap-realm.yaml:42`. The password-grant flow enabled in Docker Compose (used by the smoke test script) will not work on OpenShift. The Docker realm should be corrected to `false` to match the K8s realm and avoid demonstrating a deprecated, less-secure OAuth flow.

### 7.8 🟠 `MCAUSER('app')` missing from K8s IBM MQ channel definition
`infra/docker/config.mqsc:8` — `MCAUSER('app')` is present. `infra/k8s/infra/ibmmq/configmap-mqsc.yaml:17` — `MCAUSER` is absent. With `CHLAUTH(DISABLED)`, omitting `MCAUSER` means the channel adopts the client's asserted user, which may differ between Docker Compose and OpenShift environments. Security posture is inconsistent across deployment targets.

### 7.9 🟠 Redpanda deployed as `Deployment` not `StatefulSet` in Kubernetes
`infra/k8s/infra/redpanda/deployment.yaml` — no PVC attached. A pod restart wipes all Kafka topic data and committed offsets. After any node drain or upgrade, the demo pipeline requires a full restart from scratch. Should be a `StatefulSet` with a `PersistentVolumeClaim`.

### 7.10 🟡 `CHCKCLNT(OPTIONAL)` + `CHLAUTH(DISABLED)` — unauthenticated MQ access by design
Both docker and K8s MQ config. Combined effect: any host that can reach port 1414 can connect to QM1, write to any DEV.* queue, and read from any DEV.* queue without credentials. Acceptable for a closed demo network; must be documented and not replicated in any shared environment.

### 7.11 🟡 No `PeerAuthentication` enforcing mTLS STRICT — mesh is in permissive mode
The namespace has Istio injection (conditionally — see 7.6) but no `PeerAuthentication` CR enforces `mtls.mode: STRICT`. Without it, pods can still be reached without mTLS from outside the mesh. For a showcase that claims Istio mTLS as a feature, this should be explicit.

### 7.12 ⚪ Base images use `:latest` or unpinned tags
`keycloak:latest` (docker-compose), `ubi9/openjdk-21-runtime:latest` (all Java Dockerfiles), `nginx:1.27-alpine` (SPA), `node:20-alpine` (SPA builder). Unpinned tags make builds non-reproducible. Pin to digests or explicit minor versions for a reliable demo environment.

---

## Priority Summary

| # | Severity | Finding | File(s) |
|---|----------|---------|---------|
| 1 | 🔴 | No OTel/Tempo/Grafana in K8s — tracing showcase broken | `infra/k8s/otel/`, `infra/k8s/apps/` |
| 2 | 🔴 | No `ServiceMeshMemberRoll` — Istio injection inactive | `infra/k8s/infra/namespace.yaml` |
| 3 | 🔴 | account-verifier: dual-write; balance debited, Kafka may fail silently | `AccountVerificationService.java`, `PaymentEventPublisherImpl.java` |
| 4 | 🔴 | clearing-house: AUTO_ACK drops MQ messages on processing failure | `MqMessageListener.java` |
| 5 | 🔴 | clearing-house: non-JMS exception kills listener thread permanently | `MqMessageListener.java` |
| 6 | 🔴 | transaction-engine: Kafka redeliver → PK violation → payment stuck | `PaymentApprovedListener.java` |
| 7 | 🔴 | transaction-engine: dual-write; ledger committed, MQ send may fail | `PaymentApprovedListener.java` |
| 8 | 🔴 | account-verifier: negative amount credited to account | `AccountVerificationService.java` |
| 9 | 🔴 | account-verifier: TOCTOU balance race under concurrent payments | `AccountVerificationService.java` |
| 10 | 🔴 | K8s SPA pod crashes: nginx root user blocked by OpenShift SCC | `apps/spa-mobile-app/Dockerfile` |
| 11 | 🔴 | K8s payment-gateway: JWT issuer mismatch → all tokens rejected | `infra/k8s/apps/payment-gateway/deployment.yaml` |
| 12 | 🔴 | K8s spa-mobile-app: literal `<cluster-domain>` placeholder in env vars | `infra/k8s/apps/spa-mobile-app/deployment.yaml` |
| 13 | 🔴 | SPA: expired token sent on API call with no refresh | `src/api/payments.js` |
| 14 | 🔴 | SPA: EventSource not closed after terminal SSE status | `src/composables/usePaymentStream.js` |
| 15 | 🔴 | All services: `double` proto field for money amounts | `account.proto` |
| 16 | 🔴 | transaction-engine: MDC leak corrupts log correlation across messages | `LedgerService.java` |
| 17 | 🟠 | payment-gateway: no gRPC deadline — thread pool exhaustion | `AccountVerifierClient.java` |
| 18 | 🟠 | payment-gateway: JWT subject not forwarded downstream (CLAUDE.md requirement) | `PaymentServiceImpl.java` |
| 19 | 🟠 | K8s `MCAUSER('app')` absent from MQ channel definition | `infra/k8s/infra/ibmmq/configmap-mqsc.yaml` |
| 20 | 🟠 | `directAccessGrantsEnabled` inconsistency Docker vs K8s realm | `infra/docker/payment-realm.json` |
| 21 | 🟠 | CORS origin hardcoded `localhost:3000` — breaks OpenShift deployment | `SecurityConfig.java` |
| 22 | 🟠 | K8s Redpanda: Deployment not StatefulSet, no PVC — data lost on restart | `infra/k8s/infra/redpanda/` |
| 23 | 🟡 | payment-gateway: JWT not forwarded to gRPC (stated CLAUDE.md goal) | `PaymentServiceImpl.java` |
| 24 | 🟡 | Missing `bank.payment.transaction_id` span on Kafka consumer | `PaymentCompletedListener.java` |
| 25 | 🟡 | `traceId` not stored in ledger DB row | `V1__init_ledger.sql` |
| 26 | 🟡 | Ledger `status` always PENDING — never reflects cleared/failed outcome | `transaction_ledger` table |
| 27 | 🟡 | No mTLS STRICT PeerAuthentication policy | `infra/k8s/` |
