# T24 — Outbox Trace Context Propagation (`account-verifier`)

## Goal

Fix the distributed trace break that occurs at the outbox → Kafka boundary in `account-verifier`.
The `OutboxPoller` runs on a Quarkus scheduler thread that has no active OTel context, causing the
`traceparent` header to be absent from published Kafka records and breaking the end-to-end trace in Tempo.

**Approach (agreed):** Store `traceparent` + `tracestate` in `outbox_messages` at write time (on the
gRPC thread where context exists), then inject them directly as Kafka record headers in the poller
via `OutgoingKafkaRecordMetadata` — bypassing thread-local context restoration entirely.

**Design decisions (locked):**
- Option A: explicit Kafka header injection (not thread-local `makeCurrent()` restoration)
- Parent-child span relationship (not SpanLink)
- `GlobalOpenTelemetry.getPropagators()` for context extraction (CDI injection unavailable — `quarkus-opentelemetry` not in pom)

See `docs/review/outbox-traceparent-propagation.md` for the full design rationale and rejected alternatives.

---

## Prerequisites

- `docs/review/outbox-traceparent-propagation.md` reviewed and agreed ✓
- All existing tests in `apps/account-verifier` pass: `./mvnw test -pl apps/account-verifier`
- OpenTelemetry API already on classpath transitively (verified: `Span`, `StatusCode` used in existing code)

---

## Scope

**Only `apps/account-verifier/`** — no changes to any other service, shared module, or infra manifest.

---

## Files to Change

| File | Action |
|---|---|
| `apps/account-verifier/pom.xml` | Add explicit `opentelemetry-api` compile dependency |
| `src/main/resources/db/migration/V5__outbox_add_traceparent.sql` | New Flyway migration |
| `src/main/java/.../outbox/OutboxMessage.java` | New fields + updated factory |
| `src/main/java/.../service/AccountVerificationService.java` | Extract and persist context |
| `src/main/java/.../outbox/OutboxPoller.java` | Explicit header injection + poller span |
| `src/test/java/.../outbox/OutboxPollerTest.java` | Update / new test cases |
| `src/test/java/.../service/AccountVerificationServiceTest.java` | Update test cases |

---

## Implementation Checklist

### Step 1 — Maven: declare `opentelemetry-api` explicitly

`opentelemetry-api` is currently on the compile classpath transitively. Declare it as a direct
dependency so it is explicit and version-managed:

- [ ] In `apps/account-verifier/pom.xml`, add inside `<dependencies>`:

```xml
<!-- OTel API — for manual span ops and context propagation in outbox poller -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
    <!-- version managed by quarkus-bom -->
</dependency>
```

---

### Step 2 — Flyway Migration: `V5__outbox_add_traceparent.sql`

- [ ] Create `apps/account-verifier/src/main/resources/db/migration/V5__outbox_add_traceparent.sql`:

```sql
ALTER TABLE outbox_messages ADD COLUMN traceparent VARCHAR(55);
ALTER TABLE outbox_messages ADD COLUMN tracestate  VARCHAR(512);
```

Both columns are nullable with no default — existing rows get NULL (handled by the poller's null check).

---

### Step 3 — `OutboxMessage.java`: new fields + updated factory

- [ ] Add two nullable fields to the entity:

```java
@Column(name = "traceparent", length = 55)
public String traceparent;

@Column(name = "tracestate", length = 512)
public String tracestate;
```

- [ ] Replace the existing `of()` factory with the four-argument version:

```java
public static OutboxMessage of(String topic, String payload, String traceparent, String tracestate) {
    OutboxMessage msg = new OutboxMessage();
    msg.topic       = topic;
    msg.payload     = payload;
    msg.createdAt   = Instant.now();
    msg.sent        = false;
    msg.traceparent = traceparent;  // null when no active OTel context
    msg.tracestate  = tracestate;   // null when no tracestate
    return msg;
}
```

---

### Step 4 — `AccountVerificationService.java`: extract and persist context

New imports required:
```java
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import java.util.HashMap;
import java.util.Map;
```

- [ ] Inside `verify()`, immediately before the `outboxRepository.persist()` call, extract context:

```java
Map<String, String> propagationCarrier = new HashMap<>();
GlobalOpenTelemetry.getPropagators()
    .getTextMapPropagator()
    .inject(Context.current(), propagationCarrier, Map::put);
String traceparent = propagationCarrier.get("traceparent"); // null when agent not active
String tracestate  = propagationCarrier.get("tracestate");  // null when no tracestate
```

- [ ] Update the `outboxRepository.persist()` call to pass the extracted values:

```java
outboxRepository.persist(OutboxMessage.of("payment-approved", payload, traceparent, tracestate));
```

---

### Step 5 — `OutboxPoller.java`: explicit header injection + poller span

New imports required:
```java
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.eclipse.microprofile.reactive.messaging.Message;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.annotation.Nullable;
```

- [ ] Add a `private static final TextMapGetter<Map<String, String>>` inside the class:

```java
private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<>() {
    @Override
    public Iterable<String> keys(Map<String, String> carrier) {
        return carrier.keySet();
    }
    @Override
    public String get(Map<String, String> carrier, String key) {
        return carrier.get(key);
    }
};
```

- [ ] Replace the entire `for` loop body in `poll()` with the following logic. The
`@Transactional` annotation on the method stays — OTel spans are not JTA participants:

```java
for (OutboxMessage msg : unsent) {
    try {
        if (msg.traceparent != null) {
            publishWithContext(msg);
        } else {
            emitter.sendAndAwait(msg.payload);
        }
        msg.sent  = true;
        msg.sentAt = Instant.now();
    } catch (Exception e) {
        Log.errorf(e, "Failed to publish outbox message id=%d, will retry", msg.id);
    }
}
```

- [ ] Add the `publishWithContext` private helper method to the class:

```java
private void publishWithContext(OutboxMessage msg) {
    Map<String, String> carrier = msg.tracestate != null
        ? Map.of("traceparent", msg.traceparent, "tracestate", msg.tracestate)
        : Map.of("traceparent", msg.traceparent);

    Context parentCtx = GlobalOpenTelemetry.getPropagators()
        .getTextMapPropagator()
        .extract(Context.current(), carrier, MAP_GETTER);

    Tracer tracer = GlobalOpenTelemetry.getTracer("account-verifier");
    Span publishSpan = tracer.spanBuilder("outbox.kafka.publish")
        .setParent(parentCtx)
        .setAttribute("outbox.message.id", msg.id)
        .setAttribute("outbox.topic", msg.topic)
        .startSpan();

    try (Scope scope = publishSpan.makeCurrent()) {
        RecordHeaders headers = new RecordHeaders();
        headers.add("traceparent", msg.traceparent.getBytes(StandardCharsets.UTF_8));
        if (msg.tracestate != null && !msg.tracestate.isBlank()) {
            headers.add("tracestate", msg.tracestate.getBytes(StandardCharsets.UTF_8));
        }
        OutgoingKafkaRecordMetadata<Void> meta = OutgoingKafkaRecordMetadata.<Void>builder()
            .withHeaders(headers)
            .build();

        emitter.sendMessageAndAwait(Message.of(msg.payload).addMetadata(meta));
    } catch (Exception e) {
        publishSpan.setStatus(StatusCode.ERROR, e.getMessage());
        publishSpan.recordException(e);
        throw e;
    } finally {
        publishSpan.end();
    }
}
```

Note: `emitter` must be changed from `MutinyEmitter<String>` to `MutinyEmitter<String>` —
`sendMessageAndAwait` accepts a `Message<String>`, so the emitter type declaration stays the same;
`sendMessageAndAwait` is already defined on `MutinyEmitter<T>`.

---

### Step 6 — Tests: `OutboxPollerTest`

The test class uses `smallrye-reactive-messaging-in-memory` connector (already in test scope).

- [ ] Verify test for **non-null `traceparent`**: assert that the emitted message contains a Kafka metadata block with a `traceparent` header matching the stored value.
- [ ] Verify test for **null `traceparent`**: assert that the emitted message is a plain `String` payload with no Kafka metadata (or metadata with no `traceparent` header).
- [ ] Verify test for **Kafka send failure**: assert that `msg.sent` remains `false` and the exception is logged; the span must be ended (mock or spy on the span to verify `end()` is called).

Mock `GlobalOpenTelemetry` is not practical (it's a static global). Use `io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension` (`opentelemetry-sdk-testing`) for in-process OTel assertions, or simply verify the headers on the emitted `Message<String>` without asserting on spans — span creation is an integration concern.

The recommended approach for the poller unit test:
```java
// Use @InjectMock for OutboxRepository, @Inject for the poller under test
// Use in-memory channel via @TestConnector / @ConnectorAttribute
// After calling poll() directly, inspect the Message from the in-memory connector:
InMemoryConnector.sink("payment-approved")
    .received()
    .get(0)
    .getMetadata(OutgoingKafkaRecordMetadata.class)
    .ifPresent(meta -> {
        Header header = meta.getHeaders().lastHeader("traceparent");
        assertThat(header).isNotNull();
        assertThat(new String(header.value())).isEqualTo(expectedTraceparent);
    });
```

---

### Step 7 — Tests: `AccountVerificationServiceTest`

- [ ] Add a test that confirms `outboxRepository.persist()` is called with an `OutboxMessage`
  whose `traceparent` field is `null` when no OTel context is active (the default state in
  `@QuarkusTest` without agent injection). Capture the `OutboxMessage` argument with a Mockito
  `ArgumentCaptor`.
- [ ] If OTel context seeding is needed for a positive assertion (non-null `traceparent`), use
  `io.opentelemetry:opentelemetry-sdk-testing` to start a test span and verify the captured
  `traceparent` is non-empty. This dependency should be `test` scope only.

---

## Build Verification

After implementation, the agent must run:

```bash
# Full build including tests with coverage check
./mvnw clean verify -pl apps/account-verifier

# If the above passes, also confirm the parent build is not broken
./mvnw clean package -DskipTests
```

Both must exit 0 before the task is complete.

---

## Acceptance Criteria

- [ ] `V5__outbox_add_traceparent.sql` exists and applies cleanly via Flyway
- [ ] `OutboxMessage` entity has nullable `traceparent` (VARCHAR 55) and `tracestate` (VARCHAR 512) fields
- [ ] `OutboxMessage.of()` accepts 4 arguments; existing callers updated
- [ ] `AccountVerificationService.verify()` extracts context via `TextMapPropagator.inject()` and passes it to the outbox factory — not manual string formatting
- [ ] `OutboxPoller.poll()` uses `OutgoingKafkaRecordMetadata` to inject headers explicitly; null `traceparent` takes the no-context path
- [ ] `OutboxPoller` creates an `outbox.kafka.publish` span with the restored parent context; span ends in `finally` regardless of success/failure
- [ ] All existing tests pass; new poller and service tests cover the two traceparent paths (non-null / null)
- [ ] `./mvnw clean verify -pl apps/account-verifier` exits 0 (coverage ≥ 80%)
- [ ] No hardcoded strings introduced; `topic` still flows from the `OutboxMessage.topic` field

---

## What Is Intentionally Out of Scope

- No changes to `transaction-engine`, `clearing-house`, `payment-gateway`, or any infra manifests
- No changes to Kafka topic configuration or consumer-side trace extraction (the downstream services
  already handle W3C `traceparent` headers correctly via the OTel agent)
- No Grafana Tempo dashboard changes

---

## Subagent Execution Plan

When this task is actioned, spawn the following agents:

1. **Implementation agent** — Apply Steps 1–5 (pom, migration, entity, service, poller).
   Run in a worktree (`isolation: worktree`). Must compile cleanly before handing off.

2. **Test agent** — Apply Step 6–7 (update/add tests). Runs after implementation agent
   completes; operates in the same worktree.

3. **Verification agent** — Runs `./mvnw clean verify -pl apps/account-verifier` and reports
   pass/fail. Checks that all acceptance criteria are met. Runs after test agent completes.
