# Design Review — Outbox Pattern & OTel Trace Context Propagation

**Service:** `account-verifier`  
**Problem:** Distributed trace breaks at the DB-outbox → Kafka boundary because the scheduler thread has no active OTel context.  
**Proposed fix:** Add a `traceparent` column to `outbox_messages`, capture it on write, restore it on poll.  
**Status:** Reviewed — approach validated with gaps identified.

---

## 1. Problem Statement

The current outbox pattern creates a thread-boundary break for trace propagation:

```
gRPC server thread (has OTel context)
  └─ AccountVerificationService.verify()
       └─ accountRepository.update(...)        ← balance deduction
       └─ outboxRepository.persist(msg)        ← outbox write
       [TX commits, gRPC response sent, gRPC span ENDS]

Quarkus scheduler thread (NO OTel context, up to 2s later)
  └─ OutboxPoller.poll()
       └─ emitter.sendAndAwait(msg.payload)    ← Kafka send, traceparent = EMPTY
```

The Kafka record published by `OutboxPoller` carries no `traceparent` header. `transaction-engine` receives a root-less span and the distributed trace from `payment-gateway` → `account-verifier` → `transaction-engine` → `clearing-house` is broken here.

---

## 2. Proposed Solution (as stated)

1. Extract the active W3C `traceparent` string in `AccountVerificationService.verify()` (on the gRPC thread) and persist it as an additional column in `outbox_messages`.
2. In `OutboxPoller.poll()` (scheduler thread), read the stored `traceparent` and inject it into the outgoing Kafka message headers.

**Verdict: The approach is fundamentally correct and canonical.** The W3C Trace Context spec anticipates exactly this: serialize context into a durable store, deserialize it on the async consumer side. This is the same principle as the explicit `traceparent` JMS property in `transaction-engine` (documented in `TRACING.md` §2.3) but applied to a DB-mediated async boundary instead of a direct JMS send.

---

## 3. Gaps and Smells

### 3.1 Missing `tracestate` (W3C Compliance Gap)

The W3C Trace Context spec defines two headers: `traceparent` (required) and `tracestate` (optional extension field). In a cluster running OpenShift Service Mesh (Envoy/Istio), `tracestate` may carry Envoy-side values. Capturing only `traceparent` drops them.

**Required:** Capture and persist both headers. Add `tracestate VARCHAR(512)` alongside `traceparent VARCHAR(55)`.

> `traceparent` is always exactly 55 characters in W3C format (`00-{32hex}-{16hex}-{2hex}`), making `VARCHAR(55)` precise. `tracestate` has a spec upper bound of 512 bytes.

---

### 3.2 Context Extraction Must Go Through `TextMapPropagator`, Not Manual Formatting

The natural temptation is to build the `traceparent` string manually from `Span.current().getSpanContext()`:

```java
// WRONG — do not do this
String traceId = Span.current().getSpanContext().getTraceId();
String spanId  = Span.current().getSpanContext().getSpanId();
String traceparent = "00-" + traceId + "-" + spanId + "-01";
```

This is fragile: it hardcodes the version byte, hardcodes flags to `01`, and ignores `tracestate`. The agent or Quarkus runtime may also use a different span context implementation.

**Required:** Use `TextMapPropagator.inject()`:

```java
Map<String, String> carrier = new HashMap<>();
GlobalOpenTelemetry.getPropagators()
    .getTextMapPropagator()
    .inject(Context.current(), carrier, Map::put);

String traceparent = carrier.get("traceparent"); // null if no active trace
String tracestate  = carrier.get("tracestate");  // null if no tracestate
```

All needed classes (`GlobalOpenTelemetry`, `Context`, `TextMapPropagator`) are in `opentelemetry-api`, which is already on the compile classpath as a transitive dependency (verifiable: the service already compiles against `Span` and `StatusCode` from `io.opentelemetry.api`). No new Maven dependencies are required.

---

### 3.3 OTel CDI Injection Is Not Available Here

The `quarkus-opentelemetry` extension is **not** in `apps/account-verifier/pom.xml`. This means Quarkus does **not** register an `OpenTelemetry` CDI bean — you cannot `@Inject OpenTelemetry openTelemetry`. The only available access point is `GlobalOpenTelemetry`, which the OpenShift auto-instrumentation agent initializes at JVM startup. This is acceptable but is a runtime coupling: if the pod starts without the agent (local dev without the operator), `GlobalOpenTelemetry.getPropagators()` returns a no-op propagator, which gracefully produces a null `traceparent`. That is fine — see §3.5.

**Note for future:** If `quarkus-opentelemetry` is re-added, switch to CDI injection (`@Inject OpenTelemetry`) which is the Quarkus-idiomatic approach and avoids the static global.

---

### 3.4 Thread-Context Break When Injecting Via `makeCurrent()` in the Poller

The instinctive restoration approach in the poller is:

```java
// Intuitive but UNRELIABLE with SmallRye
Context parentCtx = ...; // extracted from stored traceparent
try (Scope scope = parentCtx.makeCurrent()) {
    emitter.sendAndAwait(msg.payload); // OTel agent should inject headers here?
}
```

This is **not reliable** in this setup. `MutinyEmitter.sendAndAwait()` routes through the SmallRye Reactive Messaging channel pipeline, which dispatches through Vert.x's internal thread pool or event loop before calling `KafkaProducer.send()`. The OTel context scope is thread-local — it is bound to the Quarkus scheduler thread. When SmallRye transitions the message to a Vert.x worker or event-loop thread for the actual Kafka send, the scope is NOT there. The OTel agent sees no active context on the Kafka producer thread and injects nothing (or injects whatever happens to be current on that event-loop thread, which is unrelated).

**Required:** Inject the Kafka headers **explicitly**, bypassing the thread-local restoration entirely. Use `OutgoingKafkaRecordMetadata` to attach headers directly to the SmallRye message before emission:

```java
// In OutboxPoller — reliable regardless of SmallRye's internal threading
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.eclipse.microprofile.reactive.messaging.Message;
import java.nio.charset.StandardCharsets;

RecordHeaders headers = new RecordHeaders();
headers.add("traceparent", msg.traceparent.getBytes(StandardCharsets.UTF_8));
if (msg.tracestate != null && !msg.tracestate.isBlank()) {
    headers.add("tracestate", msg.tracestate.getBytes(StandardCharsets.UTF_8));
}

OutgoingKafkaRecordMetadata<Void> meta = OutgoingKafkaRecordMetadata.<Void>builder()
    .withHeaders(headers)
    .build();

Message<String> kafkaMessage = Message.of(msg.payload).addMetadata(meta);
emitter.sendMessageAndAwait(kafkaMessage);
```

Both `OutgoingKafkaRecordMetadata` and `RecordHeaders` are already on the classpath via `quarkus-smallrye-reactive-messaging-kafka`. This is consistent with the explicit `traceparent` JMS property pattern already used in `transaction-engine`.

---

### 3.5 Null `traceparent` Handling

The gRPC call from `payment-gateway` to `account-verifier` is auto-instrumented — in normal operation there is always an active span when `AccountVerificationService.verify()` runs. However, two scenarios produce a null carrier:

- **Local dev without the agent injected** — the OpenShift operator is not available locally, so `GlobalOpenTelemetry` is a no-op and `inject()` produces an empty map.
- **Direct test invocation** — `@QuarkusTest` runs `verify()` without OTel context active.

In both cases `carrier.get("traceparent")` returns `null`. The `OutboxMessage` must tolerate a null `traceparent` column, and the poller must skip header injection rather than throw a NPE:

```java
// In OutboxPoller
if (msg.traceparent != null) {
    // inject headers (see §3.4)
} else {
    emitter.sendAndAwait(msg.payload); // no traceparent — trace will start fresh at transaction-engine
}
```

---

### 3.6 No Poller Span — The Outbox Publish Is Invisible in Tempo

Restoring the context and letting the agent create a Kafka producer span is good, but the poller's own work (reading the DB, marking sent) is invisible. A named span for the publish operation makes the async hop visible in Tempo's waterfall view. This is especially valuable for the showcase — it is an observable engineering demonstration.

**Recommended:** Create an explicit span around each message publish:

```java
Tracer tracer = GlobalOpenTelemetry.getTracer("account-verifier");

// For each outbox message:
Map<String, String> carrier = Map.of(
    "traceparent", msg.traceparent,
    "tracestate",  msg.tracestate != null ? msg.tracestate : ""
);
Context parentCtx = GlobalOpenTelemetry.getPropagators()
    .getTextMapPropagator()
    .extract(Context.current(), carrier, new MapTextMapGetter());

Span publishSpan = tracer.spanBuilder("outbox.kafka.publish")
    .setParent(parentCtx)
    .setAttribute("outbox.message.id", msg.id)
    .setAttribute("outbox.topic", msg.topic)
    .startSpan();
try (Scope scope = publishSpan.makeCurrent()) {
    // explicit header injection (§3.4)
    emitter.sendMessageAndAwait(kafkaMessage);
    msg.sent = true;
    msg.sentAt = Instant.now();
} catch (Exception e) {
    publishSpan.setStatus(StatusCode.ERROR, e.getMessage());
    publishSpan.recordException(e);
    throw e;
} finally {
    publishSpan.end();
}
```

The resulting trace in Tempo:

```
[gRPC server span: account-verifier] (span ends, gRPC response returned)
  └─ [outbox.kafka.publish] (created 0–2s later by poller, child of gRPC span)
       └─ [kafka.produce: payment-approved] (created by OTel agent from explicit headers)
            └─ [kafka.consume: payment-approved in transaction-engine] (continues the trace)
```

The parent span having already ended is not a problem for Tempo — it stores spans independently and reconstructs the tree by parent ID.

---

### 3.7 `@Transactional` on `poll()` Interacts With Manual Span Management

`OutboxPoller.poll()` is `@Transactional`. Span start/end does not participate in JTA, so there is no conflict. However, if the Kafka send throws and you catch-and-continue (current behaviour: `Log.errorf` and leave `sent=false`), you must end the span in the finally block regardless of success — which is what the pattern in §3.6 does.

One subtle issue: `emitter.sendMessageAndAwait()` will block until Kafka acknowledges the message. During this blocking call, the JTA transaction is still open and the DB row is locked. For the polling interval of 2 seconds and typical Kafka latency this is fine, but it is worth noting.

---

### 3.8 `OutboxMessage.of()` Factory Signature Change

The current factory:
```java
OutboxMessage.of(String topic, String payload)
```

Must be updated to carry the propagation context:
```java
OutboxMessage.of(String topic, String payload, String traceparent, String tracestate)
```

There is exactly one call site in `AccountVerificationService` — a straightforward update. The Flyway migration (`V5`) must also add the new columns before the entity change is deployed (backward compatibility: existing rows have null `traceparent`, handled by §3.5).

---

## 4. Questions for You to Decide

**Q1: Parent-child vs. SpanLink for the deferred hop?**

The OTel spec recommends `SpanLink` for async boundaries where the causal relationship is indirect (e.g., a batch poller picking up work queued by a different request). Parent-child implies the child is part of the same synchronous operation. SpanLink is semantically more precise but requires Grafana Tempo 2.x+ and the trace UI shows links differently than children — they may be less discoverable in a waterfall view.

For a showcase optimised for visual impact in Tempo's waterfall, parent-child is more intuitive. For a production system, SpanLink is more correct. Recommendation: **parent-child** for the showcase.

**Q2: Capture `traceparent` before or after the outbox `persist()`?**

Capturing `Context.current()` before the `persist()` call is correct — the active span at that point is the gRPC server span, which is the right parent. The `persist()` call itself does not change the active span context. Either location works; capturing before is slightly cleaner (the context doesn't change between validation and persistence within the same method).

**Q3: What should the poller span name be?**

`outbox.kafka.publish` is proposed above. OpenTelemetry semantic conventions for messaging use `{operation} {destination}` format — so `publish payment-approved` is fully spec-compliant. Either works; if you want Tempo's service graph to group these spans correctly, use the conventional format.

---

## 5. Alternative Approaches

### A. Explicit Header Injection (Recommended — as detailed above)
Store `traceparent`/`tracestate` in `outbox_messages`. In the poller, inject directly into Kafka headers via `OutgoingKafkaRecordMetadata`. **Best fit for this project** — aligns with the IBM MQ explicit propagation pattern already in `transaction-engine`, no new dependencies, reliable regardless of SmallRye threading.

### B. Payload Envelope (Simpler Schema, Slightly Messier)
Embed `traceparent` and `tracestate` inside the JSON payload itself (e.g., `_meta.traceparent`). The poller reads them from the JSON before sending. Avoids adding DB columns but couples tracing concerns into the business event DTO, and `transaction-engine` would need to strip the meta-fields from the `PaymentApprovedEvent` record before business processing. Not recommended — pollutes the event schema.

### C. Quarkus Vert.x Duplicate Context (Complex, Fragile)
Quarkus provides `io.vertx.core.Context` propagation that can carry OTel context across Vert.x threads. You could `captureContext()` on the gRPC thread and `run()` within the captured context in the poller. This avoids the DB column but requires passing the Vert.x context object through a non-serializable path (cannot survive a restart) — defeats the durability purpose of the outbox. Not applicable here.

### D. Debezium CDC Outbox (Eliminates Polling Thread)
Use Kafka Connect + Debezium to tail the PostgreSQL WAL and publish from the `outbox_messages` table. No scheduler thread needed — the context propagation problem moves to the Debezium connector (which would still need the `traceparent` column to inject it). Significant additional infrastructure for a showcase. Not recommended unless the project already runs Kafka Connect.

### E. No-Outbox (Direct Kafka Publish in the gRPC Transaction) — Not Viable
Publishing to Kafka directly inside the JTA transaction (using `@Transactional(NOT_SUPPORTED)` and Kafka) removes the thread break but reintroduces the dual-write problem that the outbox pattern was added to solve (`R03-account-verifier-dual-write.md`). The transaction commits in the DB but the Kafka send can fail independently, leaving balances deducted with no downstream event. This approach was already rejected.

---

## 6. Implementation Checklist

The following changes are needed to implement Option A correctly:

- [ ] **`V5__outbox_add_traceparent.sql`** — `ALTER TABLE outbox_messages ADD COLUMN traceparent VARCHAR(55); ALTER TABLE outbox_messages ADD COLUMN tracestate VARCHAR(512);` (both nullable, no default)
- [ ] **`OutboxMessage.java`** — Add `public String traceparent;` and `public String tracestate;` fields with `@Column` mappings. Update factory method signature.
- [ ] **`AccountVerificationService.java`** — After confirming the `PaymentApprovedEvent` payload is ready and before calling `outboxRepository.persist()`, call `TextMapPropagator.inject()` to extract `traceparent`/`tracestate` and pass them to `OutboxMessage.of()`.
- [ ] **`OutboxPoller.java`** — Replace `emitter.sendAndAwait(msg.payload)` with explicit `OutgoingKafkaRecordMetadata` header injection and an explicit `publishSpan` (§3.4 + §3.6). Add `MapTextMapGetter` inner class or static field.
- [ ] **Tests** — Unit tests for `OutboxPoller` should verify that when `msg.traceparent` is non-null, a `Message` with Kafka headers containing `traceparent` is emitted. When `msg.traceparent` is null, a plain `String` message is emitted without headers.

---

## 7. Summary

| Dimension | Assessment |
|---|---|
| Core idea | Correct and canonical — store context in outbox, restore on poll |
| Completeness | Incomplete — `tracestate` missing, span in poller missing |
| Thread safety of restoration | Unreliable via `makeCurrent()` through SmallRye; must use explicit header injection |
| Maven dependencies | No new deps needed — `opentelemetry-api` and Kafka/SmallRye types already on classpath |
| DB migration | Required (V5) |
| Null safety | Must be handled for local-dev / test environments |
| Recommended approach | Option A — explicit `OutgoingKafkaRecordMetadata` header injection |
