/**
 * k6 load test — observability showcase payment pipeline
 *
 * Mirrors the smoke test flow for each virtual user:
 *   1. POST /api/v1/payments      → receive 202 + transactionId
 *   2. GET  /api/v1/payments/stream/{txId}  → SSE, wait for COMPLETED
 *
 * Seeded accounts (V1__init_accounts.sql):
 *   ACC-001  Alice   $10,000  ACTIVE   ← valid source
 *   ACC-002  Bob      $5,000  ACTIVE   ← valid source
 *   ACC-003  Charlie      $0  ACTIVE   ← zero balance, will fail verification
 *   ACC-004  Diana   $25,000  SUSPENDED ← rejected, will fail
 *
 * The account-verifier applies a hold on the source balance and does NOT
 * restore it in this demo. Keep AMOUNT_USD small and reset the database
 * between runs for repeatable results:
 *   docker-compose -f infra/docker/docker-compose.yml down -v && \
 *   docker-compose -f infra/docker/docker-compose.yml up -d
 *
 * ── Environment variables ──────────────────────────────────────────────────
 *  BASE_URL      Payment gateway base URL   (default: http://localhost:8090)
 *  KEYCLOAK_URL  Keycloak base URL          (default: http://localhost:8080)
 *  KC_REALM      Keycloak realm             (default: BankDemoRealm)
 *  KC_CLIENT     OIDC client id             (default: spa-payment-client)
 *  KC_USER       Username                   (default: testuser)
 *  KC_PASSWORD   Password                   (default: password)
 *  SKIP_AUTH     "true" → skip Keycloak; send no Authorization header
 *                Use when JWT validation is handled by Istio and you are
 *                running locally without it (after task-01 refactor).
 *  AMOUNT_USD    Fixed payment amount        (default: 10.00)
 *  SSE_TIMEOUT   SSE read timeout           (default: 20s)
 *  VUS           Peak virtual users         (default: 10)
 *  DURATION      Sustain duration           (default: 2m)
 *
 * ── Run examples ──────────────────────────────────────────────────────────
 *  # Local stack (no Istio — SKIP_AUTH skips Keycloak token fetch):
 *  SKIP_AUTH=true k6 run infra/k6/payment-load-test.js
 *
 *  # Local stack with Keycloak auth:
 *  k6 run infra/k6/payment-load-test.js
 *
 *  # OpenShift with custom base URL and higher concurrency:
 *  BASE_URL=https://payment-gateway.apps.cluster.example.com \
 *  KEYCLOAK_URL=https://keycloak.apps.cluster.example.com \
 *  VUS=20 DURATION=5m \
 *  k6 run infra/k6/payment-load-test.js
 *
 *  # Quick smoke-style single-VU check:
 *  SKIP_AUTH=true k6 run --vus 1 --iterations 1 infra/k6/payment-load-test.js
 */

import http    from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

// ── Custom business metrics ────────────────────────────────────────────────
const paymentAccepted   = new Rate('payment_accepted');      // POST → 202
const pipelineCompleted = new Rate('pipeline_completed');    // SSE  → COMPLETED
const e2eLatency        = new Trend('payment_e2e_ms', true); // wall-clock ms
const paymentsSubmitted = new Counter('payments_submitted');
const pipelineErrors    = new Counter('pipeline_errors');

// ── Configuration ──────────────────────────────────────────────────────────
const BASE_URL     = __ENV.BASE_URL     || 'http://localhost:8090';
const KEYCLOAK_URL = __ENV.KEYCLOAK_URL || 'http://localhost:8080';
const KC_REALM     = __ENV.KC_REALM     || 'BankDemoRealm';
const KC_CLIENT    = __ENV.KC_CLIENT    || 'spa-payment-client';
const KC_USER      = __ENV.KC_USER      || 'testuser';
const KC_PASSWORD  = __ENV.KC_PASSWORD  || 'password';
const SKIP_AUTH    = __ENV.SKIP_AUTH    === 'true';
const AMOUNT_USD   = __ENV.AMOUNT_USD   || '10.00';
const SSE_TIMEOUT  = __ENV.SSE_TIMEOUT  || '20s';

const PEAK_VUS  = parseInt(__ENV.VUS      || '10',  10);
const DURATION  = __ENV.DURATION          || '2m';

// ── Scenarios and thresholds ───────────────────────────────────────────────
export const options = {
  scenarios: {
    payment_load: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '30s', target: PEAK_VUS },  // ramp up
        { duration: DURATION, target: PEAK_VUS },  // sustain
        { duration: '20s', target: 0 },         // ramp down
      ],
      gracefulRampDown: '20s',
    },
  },
  thresholds: {
    // Business thresholds — the pipeline must be reliable
    payment_accepted:   ['rate>=0.95'],    // ≥95% of POST /payments return 202
    pipeline_completed: ['rate>=0.90'],    // ≥90% of accepted payments reach COMPLETED via SSE
    payment_e2e_ms:     ['p(95)<15000'],   // 95th percentile end-to-end < 15 s
    // HTTP thresholds
    http_req_failed:    ['rate<0.05'],     // overall HTTP error rate < 5%
    http_req_duration:  ['p(95)<5000'],    // 95th percentile HTTP response time < 5 s
  },
};

// ── Account pairs (only ACTIVE accounts with sufficient balance) ───────────
// ACC-001 $10,000 ↔ ACC-002 $5,000
// Payments alternate direction so balance is consumed more evenly.
// At $10/payment with 10 VUs over ~3 min (~360 iterations) the minimum
// balance consumed is 180×$10=$1,800 — well within both starting balances.
const PAYMENT_PAIRS = [
  { src: 'ACC-001', dst: 'ACC-002' },
  { src: 'ACC-002', dst: 'ACC-001' },
];

// ── Setup: obtain JWT (executes once before VUs start) ────────────────────
export function setup() {
  if (SKIP_AUTH) {
    console.log('[setup] SKIP_AUTH=true — no Keycloak token will be requested');
    return { token: null, obtainedAt: 0, expiresIn: Infinity };
  }

  const url = `${KEYCLOAK_URL}/realms/${KC_REALM}/protocol/openid-connect/token`;
  const res = http.post(url, {
    client_id:  KC_CLIENT,
    grant_type: 'password',
    username:   KC_USER,
    password:   KC_PASSWORD,
  }, { tags: { name: 'keycloak_token' } });

  const ok = check(res, { '[setup] keycloak 200': (r) => r.status === 200 });
  if (!ok) {
    console.error(`[setup] Token request failed: HTTP ${res.status}\n${res.body}`);
    return { token: null, obtainedAt: 0, expiresIn: 0 };
  }

  const body       = res.json();
  const expiresIn  = body.expires_in || 300;
  console.log(`[setup] Token obtained — expires in ${expiresIn}s`);
  return { token: body.access_token, obtainedAt: Date.now(), expiresIn };
}

// ── Per-VU token state (refreshed independently if the setup token expires) ─
let vuToken  = null;
let vuExpiry = 0;

function getToken(setupData) {
  if (SKIP_AUTH) return null;

  // Setup token still valid
  const setupExpiry = setupData.obtainedAt + (setupData.expiresIn - 30) * 1000;
  if (setupData.token && Date.now() < setupExpiry) {
    return setupData.token;
  }

  // Per-VU refresh token still valid
  if (vuToken && Date.now() < vuExpiry) {
    return vuToken;
  }

  // Re-authenticate
  const url = `${KEYCLOAK_URL}/realms/${KC_REALM}/protocol/openid-connect/token`;
  const res = http.post(url, {
    client_id:  KC_CLIENT,
    grant_type: 'password',
    username:   KC_USER,
    password:   KC_PASSWORD,
  }, { tags: { name: 'keycloak_token_refresh' } });

  if (res.status !== 200) {
    console.warn(`[vu] Token refresh failed (HTTP ${res.status}), reusing stale token`);
    return setupData.token;
  }

  const body  = res.json();
  vuToken     = body.access_token;
  vuExpiry    = Date.now() + ((body.expires_in || 300) - 30) * 1000;
  return vuToken;
}

// ── Main VU function ──────────────────────────────────────────────────────
export default function (setupData) {
  const token     = getToken(setupData);
  const authHeader = token ? { Authorization: `Bearer ${token}` } : {};

  // Alternate payment direction per VU execution to spread balance consumption
  const pair = PAYMENT_PAIRS[__ITER % PAYMENT_PAIRS.length];
  const startMs = Date.now();
  let txId = null;

  // ── 1. Submit payment ──────────────────────────────────────────────────
  group('01_submit_payment', () => {
    const res = http.post(
      `${BASE_URL}/api/v1/payments`,
      JSON.stringify({
        sourceAccount:      pair.src,
        destinationAccount: pair.dst,
        amount:             parseFloat(AMOUNT_USD),
        currency:           'USD',
      }),
      {
        headers: { 'Content-Type': 'application/json', ...authHeader },
        tags:    { name: 'submit_payment' },
      },
    );

    const accepted = check(res, {
      'POST 202 Accepted':      (r) => r.status === 202,
      'transactionId present':  (r) => {
        try { return typeof r.json('transactionId') === 'string'; }
        catch { return false; }
      },
    });

    paymentAccepted.add(accepted);
    paymentsSubmitted.add(1);

    if (accepted) {
      txId = res.json('transactionId');
    } else {
      console.warn(
        `[vu] Payment submit failed: HTTP ${res.status} | ${pair.src}→${pair.dst} $${AMOUNT_USD} | ` +
        `body: ${res.body ? res.body.substring(0, 200) : '(empty)'}`,
      );
      pipelineErrors.add(1);
    }
  });

  if (!txId) {
    sleep(1);
    return;
  }

  // ── 2. Await SSE completion ────────────────────────────────────────────
  group('02_await_sse_completion', () => {
    const res = http.get(
      `${BASE_URL}/api/v1/payments/stream/${txId}`,
      {
        headers: { Accept: 'text/event-stream', ...authHeader },
        timeout: SSE_TIMEOUT,
        tags:    { name: 'sse_stream' },
      },
    );

    const completed = check(res, {
      'SSE 200 OK':          (r) => r.status === 200,
      'status COMPLETED':    (r) => r.body != null && r.body.includes('"status":"COMPLETED"'),
    });

    pipelineCompleted.add(completed);

    if (completed) {
      e2eLatency.add(Date.now() - startMs);
    } else {
      pipelineErrors.add(1);
      console.warn(
        `[vu] Pipeline incomplete: txId=${txId} | HTTP ${res.status} | ` +
        `body: ${res.body ? res.body.substring(0, 300) : '(empty)'}`,
      );
    }
  });
}

// ── Teardown ──────────────────────────────────────────────────────────────
export function teardown() {
  console.log(
    '\n[teardown] Run complete.\n' +
    `  Reminder: run "docker-compose down -v && docker-compose up -d" to reset\n` +
    `  account balances before the next load test run.\n`,
  );
}
