# R01 — 🔴 K8s: No OTel Collector, Grafana or Tempo deployed

## Problem
`infra/k8s/otel/collector-config.yaml` exists but there is no Deployment, Service, ConfigMap or
OpenTelemetryCollector CR for it anywhere under `infra/k8s/`. All app pods export traces to
`http://otel-collector:4317` — a Service that does not exist. Distributed tracing, the primary
deliverable of this showcase, cannot function on OpenShift.

Also missing: Tempo (trace storage backend) and Grafana (visualisation), which the collector config
references as `tempo:4317`.

## Files to change / create
- `infra/k8s/otel/otel-collector.yaml` — OpenTelemetryCollector CR (Operator) or plain Deployment+Service
- `infra/k8s/otel/tempo.yaml` — TempoStack or TempoMonolithic CR (Tempo Operator)
- `infra/k8s/otel/grafana.yaml` — Grafana CR (Grafana Operator) with Tempo datasource
- `infra/k8s/otel/` — any required RBAC / ServiceAccount

## Acceptance
- `oc apply -f infra/k8s/otel/` succeeds
- OTel Collector pod is running and healthy
- Apps export spans; spans are visible in Grafana → Explore → Tempo
