# TRACING.md — Distributed Tracing & Context Propagation

> The primary engineering objective of this project is **zero trace breaks** across every protocol boundary.
> This document is the authoritative reference for all OTel configuration and propagation patterns.

---

## 1. OTel Java Agent Strategy

### Why Agent-Only (No SDK Coupling)

All four Java services use **only** the OTel Java Agent for instrumentation. Application code does not declare `opentelemetry-sdk` as a Maven dependency (only `opentelemetry-api` for manual span operations when needed). This keeps the services vendor-agnostic and ensures the instrumentation layer can be swapped or upgraded independently.

### Agent Packaging

The `opentelemetry-javaagent.jar` is baked into every Java service container image at build time. Add this to each service's `Dockerfile` (or Jib configuration):

```dockerfile
# Download OTel agent during image build
ARG OTEL_AGENT_VERSION=2.6.0
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar \
    /opt/otel/opentelemetry-javaagent.jar
```

For Jib-based builds (no Dockerfile), add the agent download as an extra build step or use a base image that includes the agent.

### Agent Activation

Activated via the `JAVA_TOOL_OPTIONS` environment variable in every container (Docker Compose and K8s Deployments):

```yaml
env:
  - name: JAVA_TOOL_OPTIONS
    value: "-javaagent:/opt/otel/opentelemetry-javaagent.jar"
  - name: OTEL_TRACES_EXPORTER
    value: "otlp"
  - name: OTEL_EXPORTER_OTLP_PROTOCOL
    value: "grpc"
  - name: OTEL_EXPORTER_OTLP_ENDPOINT
    value: "http://otel-collector:4317"
  - name: OTEL_SERVICE_NAME
    value: "<service-name>"           # Set per-service (see table below)
  - name: OTEL_METRICS_EXPORTER
    value: "none"                     # Metrics disabled — traces only
  - name: OTEL_LOGS_EXPORTER
    value: "none"                     # Logs disabled — traces only
  - name: OTEL_PROPAGATORS
    value: "tracecontext,baggage"     # W3C Trace Context only
```

### Service Name Values

| Service | `OTEL_SERVICE_NAME` |
|---|---|
| `payment-gateway` | `payment-gateway` |
| `account-verifier` | `account-verifier` |
| `transaction-engine` | `transaction-engine` |
| `clearing-house` | `clearing-house` |

---

## 2. Cross-Protocol Propagation Reference

### Boundary 1: HTTP → gRPC (`payment-gateway` → `account-verifier`)

**How it works:**
1. OTel Agent intercepts the incoming HTTP request at `payment-gateway` and extracts the W3C `traceparent` / `tracestate` headers, creating the root span.
2. When `payment-gateway` calls `account-verifier` via gRPC, the Agent intercepts the outbound gRPC call and automatically injects the active span context into the **gRPC metadata** (HTTP/2 headers).
3. The Agent on `account-verifier` intercepts the inbound gRPC call, extracts the metadata, and continues the trace as a child span.

**Required:** No application code changes. This is fully automatic.

**Verify:** In Grafana Tempo, the gRPC call should appear as a child span of the HTTP POST span, sharing the same `traceId`.

---

### Boundary 2: gRPC → Kafka (`account-verifier` → `transaction-engine`)

**How it works:**
1. The OTel Agent intercepts the Kafka producer in `account-verifier` (SmallRye Reactive Messaging uses the standard Kafka client underneath).
2. The Agent injects the active trace context into **Kafka record headers** as binary-encoded strings.

**Header injected automatically:**
```
traceparent: 00-<traceId>-<spanId>-01
tracestate:  (empty if none)
```

3. The OTel Agent on `transaction-engine` intercepts the Kafka consumer, reads the headers, and creates a new child span linked to the producer span.

**Required:** No application code changes for the Kafka boundaries. This is fully automatic.

**Verify:** The Kafka consumer span in `transaction-engine` should share the same `traceId` as the upstream spans.

---

### Boundary 3: Kafka → JMS/IBM MQ (`transaction-engine` → `clearing-house`)

**This is the highest-risk boundary.** The OTel Agent provides automatic JMS instrumentation for modern providers (ActiveMQ Artemis, etc.), but IBM MQ's JMS implementation may not be fully intercepted by the agent in all configurations. Therefore, **explicit programmatic propagation is mandatory** as a safety net.

**In `transaction-engine` (producer side):**

```java
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;

// Extract the current traceparent as a W3C-formatted string
private String getCurrentTraceparent() {
    StringBuilder sb = new StringBuilder();
    GlobalOpenTelemetry.getPropagators()
        .getTextMapPropagator()
        .inject(Context.current(), sb, (carrier, key, value) -> {
            if ("traceparent".equals(key)) carrier.append(value);
        });
    return sb.toString();
}

// Inside the JmsTemplate send call:
jmsTemplate.send("DEV.QUEUE.CLEARING", session -> {
    TextMessage message = session.createTextMessage(jsonPayload);
    message.setStringProperty("traceparent", getCurrentTraceparent());
    message.setStringProperty("transactionId", transactionId);
    return message;
});
```

**In `clearing-house` (consumer side):**

```java
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import javax.jms.TextMessage;

// Custom getter for JMS String properties
private static final TextMapGetter<TextMessage> JMS_GETTER = new TextMapGetter<>() {
    @Override
    public Iterable<String> keys(TextMessage carrier) {
        return List.of("traceparent", "tracestate");
    }

    @Override
    public String get(TextMessage carrier, String key) {
        try {
            return carrier.getStringProperty(key);
        } catch (JMSException e) {
            return null;
        }
    }
};

// In the JMS message listener:
public void onMessage(Message message) {
    TextMessage textMessage = (TextMessage) message;
    Context extractedContext = GlobalOpenTelemetry.getPropagators()
        .getTextMapPropagator()
        .extract(Context.current(), textMessage, JMS_GETTER);

    try (Scope scope = extractedContext.makeCurrent()) {
        Span span = GlobalOpenTelemetry.getTracer("clearing-house")
            .spanBuilder("clearing-house.process")
            .setParent(extractedContext)
            .startSpan();
        try (Scope spanScope = span.makeCurrent()) {
            processClearingMessage(textMessage);
        } finally {
            span.end();
        }
    }
}
```

**Verify:** The clearing-house processing span must share the same `traceId` as all upstream spans. This is the most critical check.

---

### Boundary 4: Kafka → SSE (`payment-gateway` Kafka consumer to SSE emitter)

This boundary is internal to `payment-gateway`. The Kafka consumer span and the SSE event push are part of the same service. The OTel Agent links the Kafka consumer span automatically. No special handling needed — the SSE push is a terminal action, not a new service call.

---

## 3. Custom Span Attributes (Manual SDK Usage)

Use `opentelemetry-api` (not the full SDK) for manual attribute tagging. These attributes make traces queryable in Grafana Tempo by business context:

### Required Attributes per Service

**`payment-gateway`** — on the POST /api/v1/payments span:
```java
Span.current().setAttribute("bank.payment.transaction_id", transactionId);
Span.current().setAttribute("bank.payment.source_account", request.getSourceAccount());
Span.current().setAttribute("bank.payment.amount", request.getAmount().toString());
Span.current().setAttribute("bank.payment.currency", request.getCurrency());
```

**`account-verifier`** — on the gRPC handler span:
```java
Span.current().setAttribute("bank.payment.transaction_id", request.getTransactionId());
Span.current().setAttribute("bank.account.source", request.getSourceAccount());
Span.current().setAttribute("bank.account.approved", approved);
```

**`transaction-engine`** — on the Kafka consumer span:
```java
Span.current().setAttribute("bank.payment.transaction_id", transactionId);
Span.current().setAttribute("bank.ledger.record_id", ledgerRecord.getTransactionId());
```

**`clearing-house`** — on the JMS consumer span:
```java
Span.current().setAttribute("bank.payment.transaction_id", transactionId);
Span.current().setAttribute("bank.clearing.status", clearingResult);
```

---

## 4. Error Span Recording

Any caught exception that affects business processing **must** be recorded on the active span. Use this pattern consistently across all services:

**Spring Boot services:**
```java
try {
    // business logic
} catch (Exception ex) {
    Span span = Span.current();
    span.setStatus(StatusCode.ERROR, ex.getMessage());
    span.recordException(ex);
    throw ex;
}
```

**Quarkus services:**
```java
try {
    // business logic
} catch (Exception ex) {
    Span.current().setStatus(StatusCode.ERROR, ex.getMessage());
    Span.current().recordException(ex);
    throw ex;
}
```

Do **not** silently swallow exceptions that affect trace continuity. All error paths must be visible in Tempo.

---

## 5. Dead-Letter Queue Tracing

Messages that fail processing and are sent to DLT topics/queues should preserve the original trace context. This allows debugging the failure to be correlated with the original transaction.

**Spring Kafka DLT configuration (in `payment-gateway` and `transaction-engine`):**

```java
@Bean
public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(KafkaTemplate<?, ?> template) {
    return new DeadLetterPublishingRecoverer(template,
        (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1));
}

@Bean
public DefaultErrorHandler errorHandler(DeadLetterPublishingRecoverer recoverer) {
    ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
    backOff.setMaxAttempts(3);
    return new DefaultErrorHandler(recoverer, backOff);
}
```

The OTel Agent propagates trace context into DLT messages automatically since they are standard Kafka producer calls.

---

## 6. OTel Collector — Example Configuration

> **Note:** The OTel Collector is deployed **outside this project** (typically as part of the OpenShift cluster infrastructure with the Red Hat OpenTelemetry Operator). This configuration is provided as a **reference guide** for the infrastructure team.

File: `infra/otel/collector-config.yaml`

```yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318

processors:
  batch:
    timeout: 1s
    send_batch_size: 1024
  memory_limiter:
    limit_mib: 400
    spike_limit_mib: 100
    check_interval: 5s

exporters:
  otlp/tempo:
    endpoint: tempo:4317    # Grafana Tempo gRPC endpoint
    tls:
      insecure: true
  logging:
    verbosity: detailed     # For debugging only — remove in production

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [memory_limiter, batch]
      exporters: [otlp/tempo]
  telemetry:
    logs:
      level: warn
```

### Key Points

- The Collector listens on `4317` (gRPC) and `4318` (HTTP/protobuf) for OTLP.
- All services point their `OTEL_EXPORTER_OTLP_ENDPOINT` to the Collector, not directly to Tempo.
- The Collector forwards to Grafana Tempo using OTLP/gRPC.
- On OpenShift with the Red Hat OpenTelemetry Operator, an `OpenTelemetryCollector` CR replaces this manual config.

---

## 7. Local Development OTel Setup

For local Docker Compose development, you have two options:

**Option A — No telemetry (fastest):** Set `OTEL_TRACES_EXPORTER=none` in docker-compose.yml to disable all trace export. Services still run the agent but discard spans.

**Option B — Local Grafana stack:** Add Grafana Tempo + Grafana to docker-compose.yml and point the Collector (or services directly) to Tempo. A minimal addition:

```yaml
tempo:
  image: grafana/tempo:latest
  command: ["-config.file=/etc/tempo.yaml"]
  volumes:
    - ./tempo-local.yaml:/etc/tempo.yaml
  ports:
    - "3200:3200"   # Tempo query API
    - "4317:4317"   # OTLP gRPC

grafana:
  image: grafana/grafana:latest
  ports:
    - "3001:3000"
  environment:
    GF_AUTH_ANONYMOUS_ENABLED: "true"
```

Set `OTEL_EXPORTER_OTLP_ENDPOINT=http://tempo:4317` in all service definitions.
