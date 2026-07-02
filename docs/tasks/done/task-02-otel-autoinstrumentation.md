# Task 02 — Migrate to OpenShift OTel Auto-instrumentation

## Goal

Remove OTel agent management from container images, build tooling, and framework-level OTel extensions. The OpenShift OpenTelemetry Operator injects the Java agent at pod startup via an `Instrumentation` CR (Task 03) and a pod annotation. The result is auto-instrumentation working on any plain Java application with zero agent-lifecycle code. Spring services retain `opentelemetry-api` only for lightweight business-context span attributes — these are additive to auto-instrumentation and do not conflict.

---

## Decisions

| Question | Decision |
|---|---|
| Custom business span attributes (`bank.payment.*`, `bank.ledger.*`) | Keep — `opentelemetry-api` stays in Spring services; manual `setAttribute` calls are preserved |
| Quarkus `quarkus-opentelemetry` extension | Remove entirely — it registers its own SDK at build time and conflicts with the injected agent |
| `Instrumentation` CR YAML | Separate Task 03 |

---

## Files to Change

### 1. Dockerfiles — remove the agent download stage

All four Dockerfiles are identical multi-stage builds that download `opentelemetry-javaagent.jar`. Collapse to a single-stage image.

| Dockerfile |
|---|
| `apps/payment-gateway/Dockerfile` |
| `apps/transaction-engine/Dockerfile` |
| `apps/account-verifier/Dockerfile` |
| `apps/clearing-house/Dockerfile` |

**Remove from every Dockerfile:**
```dockerfile
# Stage 1 — delete entirely
FROM registry.access.redhat.com/ubi9/openjdk-21:latest AS agent-downloader
RUN curl -fsSL -o /tmp/opentelemetry-javaagent.jar \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.10.0/opentelemetry-javaagent.jar

# These three lines in Stage 2 — delete
RUN mkdir -p /opt/otel
COPY --from=agent-downloader /tmp/opentelemetry-javaagent.jar /opt/otel/opentelemetry-javaagent.jar
RUN chmod 644 /opt/otel/opentelemetry-javaagent.jar
```

The `FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:latest` line remains as the single base image.

---

### 2. pom.xml — remove `quarkus-opentelemetry` from Quarkus services

The extension initializes its own OTel SDK and OTLP exporter, which conflicts with the agent injected by the operator. Remove it completely.

**`apps/account-verifier/pom.xml`** lines ~51-54:
```xml
<!-- REMOVE -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-opentelemetry</artifactId>
</dependency>
```

**`apps/clearing-house/pom.xml`** lines ~35-38:
```xml
<!-- REMOVE -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-opentelemetry</artifactId>
</dependency>
```

After removal, check `application.properties` in both services for any `quarkus.otel.*` properties and remove them.

#### Spring Boot services — `opentelemetry-api` stays

`apps/payment-gateway/pom.xml` and `apps/transaction-engine/pom.xml` both declare `io.opentelemetry:opentelemetry-api`. This dependency is kept. The OTel API jar (~200KB, zero SDK code) is compatible with any agent — it is a pure interface library. The manual `setAttribute` calls in `PaymentServiceImpl`, `LedgerService`, and `PaymentApprovedListener` are preserved as-is.

---

### 3. K8s Deployments — remove manual env vars, add annotation

The operator sets `JAVA_TOOL_OPTIONS` and all `OTEL_*` vars automatically via the `Instrumentation` CR. Keeping them manually would cause double-agent activation (JVM crash) or override the operator's config.

#### Remove from all four deployment manifests:

```yaml
# REMOVE these env vars wherever they appear
- name: JAVA_TOOL_OPTIONS
- name: OTEL_TRACES_EXPORTER
- name: OTEL_EXPORTER_OTLP_PROTOCOL
- name: OTEL_EXPORTER_OTLP_ENDPOINT
- name: OTEL_METRICS_EXPORTER
- name: OTEL_LOGS_EXPORTER
- name: OTEL_PROPAGATORS
- name: OTEL_SERVICE_NAME
```

| Deployment manifest |
|---|
| `infra/k8s/apps/payment-gateway/deployment.yaml` |
| `infra/k8s/apps/transaction-engine/deployment.yaml` |
| `infra/k8s/apps/account-verifier/deployment.yaml` |
| `infra/k8s/apps/clearing-house/deployment.yaml` |

#### Add the injection annotation to `spec.template.metadata.annotations`:

```yaml
spec:
  template:
    metadata:
      annotations:
        instrumentation.opentelemetry.io/inject-java: "true"
```

> **Note:** The annotation value `"true"` tells the operator to look for an `Instrumentation` CR named `default` in the same namespace. If the CR will use a different name, the annotation value should be the CR name (e.g., `"otel-instrumentation"`). Align with Task 03.

---

### 4. Parent pom.xml — remove Jib `JAVA_TOOL_OPTIONS`

**File:** `pom.xml` line ~121

```xml
<!-- REMOVE this <environment> block from the Jib plugin configuration -->
<environment>
    <JAVA_TOOL_OPTIONS>-javaagent:/opt/otel/opentelemetry-javaagent.jar</JAVA_TOOL_OPTIONS>
</environment>
```

This prevents Jib-built images from trying to load a non-existent agent at startup.

---

## Acceptance Criteria

- [ ] All four Dockerfiles are single-stage — no `agent-downloader` stage, no `/opt/otel/` directory, no `curl` for the javaagent
- [ ] `io.quarkus:quarkus-opentelemetry` removed from `account-verifier/pom.xml` and `clearing-house/pom.xml`
- [ ] No `quarkus.otel.*` properties remain in account-verifier or clearing-house `application.properties`
- [ ] `io.opentelemetry:opentelemetry-api` remains in payment-gateway and transaction-engine `pom.xml` (intentional)
- [ ] `JAVA_TOOL_OPTIONS` env var removed from all four K8s deployment manifests
- [ ] All `OTEL_*` env vars removed from all four K8s deployment manifests
- [ ] `instrumentation.opentelemetry.io/inject-java: "true"` annotation added to `spec.template.metadata.annotations` in all four deployments
- [ ] Jib `<environment>` block with `JAVA_TOOL_OPTIONS` removed from root `pom.xml`
- [ ] `./mvnw clean package -DskipTests` succeeds across all modules
- [ ] No compilation errors (manual OTel API calls in Spring services compile cleanly against the kept dependency)
