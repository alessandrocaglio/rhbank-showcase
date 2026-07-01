# T23 · k6 Payment Pipeline Load Test

**Phase:** 9 — Observability validation  
**Status:** done  
**Depends on:** T22 (local run), task-01 (JWT → Istio), task-02 (OTel auto-instrumentation)

---

## Goal

Provide a repeatable, configurable k6 load test that drives concurrent payment requests through
the full pipeline — HTTP → gRPC → Kafka → JMS → Kafka → SSE — and asserts business-level
reliability and latency thresholds. The script is the primary tool for validating pipeline
behaviour under load and for generating meaningful distributed traces in Grafana Tempo.

---

## Script location

```
infra/k6/payment-load-test.js
```

---

## What the script does

Each virtual user (VU) iterates through the same two-step flow that the smoke test performs:

| Step | Action | Assertion |
|---|---|---|
| 1 | `POST /api/v1/payments` | HTTP 202, `transactionId` in body |
| 2 | `GET /api/v1/payments/stream/{txId}` (SSE) | HTTP 200, body contains `"status":"COMPLETED"` |

End-to-end latency is measured from the POST request to the SSE `COMPLETED` event — covering
the full async pipeline (gRPC account verification → Kafka → IBM MQ → Kafka → SSE resolution).

---

## Load profile

| Stage | Duration | VUs |
|---|---|---|
| Ramp up | 30 s | 1 → 10 |
| Sustain | 2 min | 10 |
| Ramp down | 20 s | 10 → 0 |

Total test duration: ~3 minutes. Override with `VUS` and `DURATION` env vars.

---

## Thresholds (pass / fail)

| Metric | Threshold | Meaning |
|---|---|---|
| `payment_accepted` | ≥ 95 % | POST /payments must return 202 reliably |
| `pipeline_completed` | ≥ 90 % | At least 90 % of accepted payments reach COMPLETED via SSE |
| `payment_e2e_ms p95` | < 15 000 ms | 95th-percentile end-to-end latency under 15 s |
| `http_req_failed` | < 5 % | Overall HTTP error rate |
| `http_req_duration p95` | < 5 000 ms | 95th-percentile individual HTTP response time |

k6 exits with a non-zero code if any threshold is breached, making the script suitable for CI gates.

---

## Custom metrics

| Metric | Type | Description |
|---|---|---|
| `payment_accepted` | Rate | Fraction of POST requests that returned 202 |
| `pipeline_completed` | Rate | Fraction of transactions that reached COMPLETED |
| `payment_e2e_ms` | Trend | Wall-clock ms from POST to COMPLETED (p50, p90, p95, p99) |
| `payments_submitted` | Counter | Total payment attempts |
| `pipeline_errors` | Counter | Total failures (submit + SSE combined) |

---

## Account strategy

Only two accounts are valid sources in the seeded database:

| Account | Owner | Balance | Status |
|---|---|---|---|
| `ACC-001` | Alice Martin | $10,000 | ACTIVE |
| `ACC-002` | Bob Johnson | $5,000 | ACTIVE |
| `ACC-003` | Charlie Brown | $0 | ACTIVE — will fail (zero balance) |
| `ACC-004` | Diana Prince | $25,000 | SUSPENDED — will fail |

Payments alternate direction (`ACC-001 → ACC-002` on even iterations, `ACC-002 → ACC-001`
on odd) so balance is consumed symmetrically. At $10 per payment with 10 VUs over ~3 minutes
(~360 total iterations), the maximum balance consumed per account is ~$1,800 — well within
both starting balances.

**The account-verifier applies a hold but does not restore it in this demo.** Reset the
database between load test runs:

```bash
docker-compose -f infra/docker/docker-compose.yml down -v
docker-compose -f infra/docker/docker-compose.yml up -d
# or
podman-compose -f infra/docker/docker-compose.yml down -v && podman-compose ... up -d
```

---

## Authentication

The script supports two auth modes controlled by `SKIP_AUTH`:

**With Keycloak** (default — required on OpenShift with Istio `RequestAuthentication`):
- `setup()` fetches a token using the `password` grant.
- The token is shared across VUs for the duration of the test.
- Each VU independently re-authenticates if the setup token expires (configurable Keycloak
  token lifetime; default 5 min is sufficient for a 3-minute run).

**Without Keycloak** (`SKIP_AUTH=true` — local dev after task-01 JWT refactor):
- No `Authorization` header is sent.
- Suitable when running locally without Istio enforcing JWT.

---

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `BASE_URL` | `http://localhost:8090` | Payment gateway base URL |
| `KEYCLOAK_URL` | `http://localhost:8080` | Keycloak base URL |
| `KC_REALM` | `BankDemoRealm` | Keycloak realm |
| `KC_CLIENT` | `spa-payment-client` | OIDC client id |
| `KC_USER` | `testuser` | Username for password grant |
| `KC_PASSWORD` | `password` | Password for password grant |
| `SKIP_AUTH` | `false` | Set `true` to skip Keycloak auth |
| `AMOUNT_USD` | `10.00` | Fixed payment amount per iteration |
| `SSE_TIMEOUT` | `20s` | Max time to wait for SSE COMPLETED event |
| `VUS` | `10` | Peak virtual users |
| `DURATION` | `2m` | Sustain duration |

---

## Run examples

```bash
# Prerequisites
k6 version   # must be installed — https://k6.io/docs/getting-started/installation/

# Local stack — no Istio, JWT removed from gateway (task-01):
SKIP_AUTH=true k6 run infra/k6/payment-load-test.js

# Local stack with Keycloak auth:
k6 run infra/k6/payment-load-test.js

# Single-iteration smoke check (equivalent to showcase.sh smoke):
SKIP_AUTH=true k6 run --vus 1 --iterations 1 infra/k6/payment-load-test.js

# Higher concurrency, longer duration:
SKIP_AUTH=true VUS=20 DURATION=5m k6 run infra/k6/payment-load-test.js

# OpenShift deployment:
BASE_URL=https://payment-gateway.apps.cluster.example.com \
KEYCLOAK_URL=https://keycloak.apps.cluster.example.com \
VUS=20 DURATION=5m \
k6 run infra/k6/payment-load-test.js

# Output results as JSON for post-processing:
SKIP_AUTH=true k6 run --out json=results.json infra/k6/payment-load-test.js
```

---

## Installing k6

```bash
# Fedora / RHEL / CentOS
sudo dnf install https://dl.k6.io/rpm/repo.rpm
sudo dnf install k6

# macOS
brew install k6

# Binary download
curl -L https://github.com/grafana/k6/releases/latest/download/k6-linux-amd64.tar.gz \
  | tar xz && sudo mv k6-linux-amd64/k6 /usr/local/bin/
```

---

## Observability during the test

While the test runs, every payment generates a distributed trace spanning all five services.
Open Grafana Tempo (or the Jaeger-compatible UI) and search by `bank.payment.transaction_id`
to inspect individual traces. Under load, the Tempo trace list will show concurrent in-flight
payments with their full HTTP → gRPC → Kafka → JMS → Kafka → SSE spans.

If the OTel Collector is configured (task-03), traces are exported automatically. No
application code changes are needed — the Java agent handles all instrumentation.

---

## Acceptance criteria

- [x] `infra/k6/payment-load-test.js` created
- [x] Script passes with `SKIP_AUTH=true --vus 1 --iterations 1`
- [x] All five thresholds defined and documented
- [x] Account balance strategy documented (symmetric alternation, reset instructions)
- [x] Auth modes documented (Keycloak + SKIP_AUTH)
- [x] Run examples cover local, local+auth, and OpenShift scenarios

---

## Related tasks

- `task-01-jwt-to-istio.md` — JWT validation moved to Istio; `SKIP_AUTH=true` enables local testing
- `task-02-otel-autoinstrumentation.md` — OTel agent injected by operator; load test generates traces automatically
- `task-03-otel-instrumentation-cr.md` — Instrumentation CR required for traces to export on OpenShift
- `T22-run-project-locally.md` — Single-iteration smoke test; k6 replaces the curl-based approach at scale
