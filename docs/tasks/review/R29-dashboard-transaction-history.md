# R29 — Feature: Dashboard transaction history list

## Problem / Current state

`DashboardView.vue` renders the "Recent Activity" section with a static empty state:
"No recent transactions / Submit a payment to see it here". No API is called, and no
historical data is fetched from anywhere. The `transaction_ledger` table in `ledger_db`
(PostgreSQL, managed by transaction-engine) already holds all completed and in-flight
records, but transaction-engine exposes no HTTP endpoint — its `pom.xml` does not include
`spring-boot-starter-web`; it only has `spring-boot-starter-data-jpa`, `actuator`, and
`spring-boot-starter-json`. The actuator runs on port 8080 but its endpoints are limited
to `health` and `info`.

---

## Architecture analysis

Three options were evaluated:

**Option A — transaction-engine exposes a REST endpoint**
Requires adding `spring-boot-starter-web` to transaction-engine's `pom.xml`, opening a
new HTTP port to the mesh, and writing a `@RestController`. This changes the deployment
model of a headless consumer, creates a new service endpoint in the K8s mesh that needs
a `Service` object and potentially a `VirtualService`, and blurs the single-responsibility
boundary.

**Option B — payment-gateway queries ledger_db directly via a second datasource**
Requires adding `spring-boot-starter-data-jpa` and a second `DataSource` bean with JDBC
URL pointing at `ledger_db` to payment-gateway. This bypasses the service boundary and
couples two services at the database layer. It also breaks trace propagation (no
inter-service span linking through DB queries).

**Option C — payment-gateway keeps an in-memory cache of completed transactions**
payment-gateway already receives every `payment-completed` Kafka event in
`PaymentCompletedListener`. At that point it knows `transactionId`, `status`, `clearedAt`,
and `detail`. The original payment request (source account, destination account, amount,
currency) is passed through the Kafka event chain and is available on the `payment-completed`
topic message payload via `PaymentCompletedEvent`. An in-memory `ConcurrentLinkedDeque`
capped at a configurable size (default 50) can be maintained per-user (keyed on
`preferred_username`) or globally, depending on the demo audience.

**Recommendation: Option C — in-memory cache in payment-gateway**

Justification:
- Zero new service endpoints, ports, or K8s objects.
- payment-gateway already consumes `payment-completed`; the cache is a trivial addition.
- Trace-correct: data flows through the existing traced Kafka path; the cache is a
  read-only view of already-observed events.
- Demo-appropriate: a cap of 50 entries is plenty for a showcase. There is no persistence
  across payment-gateway restarts, which is acceptable for a demo.
- The `payment-completed` Kafka topic carries `transactionId` and `status` but currently
  NOT the full payment detail (source/destination/amount/currency). The `PaymentCompletedEvent`
  record only has `(transactionId, status, clearedAt, detail)`. This means payment-gateway
  must correlate the original payment parameters at event-receive time.

  The cleanest way to get full payment parameters into the cache without DB queries is to
  store them in payment-gateway at the moment `initiatePayment` is called (step 2 of the
  pipeline). `PaymentServiceImpl` already generates `transactionId`; it simply needs to
  also stash `(sourceAccount, destinationAccount, amount, currency)` in a short-lived
  `ConcurrentHashMap<String, PendingPaymentRecord>` in the gateway. When
  `PaymentCompletedListener` fires, it looks up the pending record by `transactionId`,
  assembles the full `TransactionSummary`, stores it in the history deque, and removes
  the pending entry.

---

## User to account mapping

For the transaction history, the simplest demo-appropriate approach is to return all
recent transactions globally (no per-user filtering) and display them in reverse-chronological
order. The dashboard shows "Recent Activity" for the app, not "my transactions only". This
avoids the need for a Keycloak-username-to-account mapping in this feature (R28 covers that
mapping for balance). If per-user filtering is desired in the future, the source account can
be matched against the `accountId` returned from the balance endpoint.

---

## Implementation plan

### Layer 1: payment-gateway — pending payment registry

**File (new):** `apps/payment-gateway/src/main/java/com/showcase/gateway/dto/PendingPaymentRecord.java`

```java
package com.showcase.gateway.dto;

import java.math.BigDecimal;

public record PendingPaymentRecord(
        String transactionId,
        String sourceAccount,
        String destinationAccount,
        BigDecimal amount,
        String currency
) {}
```

**File (new):** `apps/payment-gateway/src/main/java/com/showcase/gateway/service/TransactionHistoryService.java`

This is a Spring `@Service` (singleton) that serves as both the pending-payment registry
and the completed-transaction history store.

```java
package com.showcase.gateway.service;

import com.showcase.gateway.dto.PendingPaymentRecord;
import com.showcase.gateway.dto.TransactionSummary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
public class TransactionHistoryService {

    // Max entries kept in memory.  Configurable via app.history.max-size.
    private final int maxSize;

    // Short-lived: entry added on payment initiation, removed on payment-completed.
    private final Map<String, PendingPaymentRecord> pending = new ConcurrentHashMap<>();

    // Completed transactions, most-recent first.
    private final Deque<TransactionSummary> history = new ConcurrentLinkedDeque<>();

    public TransactionHistoryService(
            @org.springframework.beans.factory.annotation.Value("${app.history.max-size:50}") int maxSize) {
        this.maxSize = maxSize;
    }

    /** Called by PaymentServiceImpl immediately after generating transactionId. */
    public void registerPending(PendingPaymentRecord record) {
        pending.put(record.transactionId(), record);
    }

    /**
     * Called by PaymentCompletedListener.
     * Looks up the pending record, builds a TransactionSummary, adds it to history,
     * trims to maxSize, and removes the pending entry.
     * Returns the summary, or null if no pending record was found (idempotent guard).
     */
    public TransactionSummary complete(String transactionId, String status,
                                       String clearedAt, String detail) {
        PendingPaymentRecord rec = pending.remove(transactionId);
        TransactionSummary summary;
        if (rec != null) {
            summary = new TransactionSummary(
                    transactionId,
                    rec.sourceAccount(),
                    rec.destinationAccount(),
                    rec.amount(),
                    rec.currency(),
                    status,
                    clearedAt);
        } else {
            // Fallback: we lost the pending record (e.g., gateway restarted).
            // Still record what we know from the completed event.
            summary = new TransactionSummary(
                    transactionId, "?", "?", null, "?", status, clearedAt);
        }
        history.addFirst(summary);
        while (history.size() > maxSize) {
            history.pollLast();
        }
        return summary;
    }

    /** Returns up to `limit` most-recent completed transactions (snapshot copy). */
    public List<TransactionSummary> getRecent(int limit) {
        List<TransactionSummary> result = new ArrayList<>();
        for (TransactionSummary s : history) {
            if (result.size() >= limit) break;
            result.add(s);
        }
        return Collections.unmodifiableList(result);
    }
}
```

**File (new):** `apps/payment-gateway/src/main/java/com/showcase/gateway/dto/TransactionSummary.java`

```java
package com.showcase.gateway.dto;

import java.math.BigDecimal;

public record TransactionSummary(
        String transactionId,
        String sourceAccount,
        String destinationAccount,
        BigDecimal amount,
        String currency,
        String status,      // "COMPLETED" | "FAILED"
        String clearedAt    // ISO-8601 timestamp string from clearing-house
) {}
```

### Layer 2: payment-gateway — wire the registry into existing classes

**File:** `apps/payment-gateway/src/main/java/com/showcase/gateway/service/PaymentServiceImpl.java`

Inject `TransactionHistoryService` via constructor and call `registerPending` after
generating `transactionId`, before the gRPC call:

```java
public PaymentServiceImpl(AccountVerifierClient accountVerifierClient,
                          TransactionHistoryService transactionHistoryService) {
    this.accountVerifierClient = accountVerifierClient;
    this.transactionHistoryService = transactionHistoryService;
}

@Override
public PaymentResponse initiatePayment(PaymentRequest request) {
    String transactionId = UUID.randomUUID().toString();

    // Register immediately so history can reconstruct full details on completion.
    transactionHistoryService.registerPending(new PendingPaymentRecord(
            transactionId,
            request.sourceAccount(),
            request.destinationAccount(),
            request.amount(),
            request.currency()));

    // ... rest of existing code unchanged ...
}
```

**File:** `apps/payment-gateway/src/main/java/com/showcase/gateway/messaging/PaymentCompletedListener.java`

Inject `TransactionHistoryService` via constructor and call `complete` at the point where
the SSE emitter is resolved, after deserializing the event:

```java
public PaymentCompletedListener(SseEmitterService sseEmitterService,
                                ObjectMapper objectMapper,
                                TransactionHistoryService transactionHistoryService) {
    this.sseEmitterService = sseEmitterService;
    this.objectMapper = objectMapper;
    this.transactionHistoryService = transactionHistoryService;
}

@KafkaListener(...)
public void onPaymentCompleted(@Payload String payload) {
    try {
        PaymentCompletedEvent event = objectMapper.readValue(payload, PaymentCompletedEvent.class);

        // Update history cache BEFORE resolving SSE so the dashboard can poll
        // immediately after the SSE event arrives.
        transactionHistoryService.complete(
                event.transactionId(),
                event.status(),
                event.clearedAt(),
                event.detail());

        PaymentStatusEvent sseEvent = new PaymentStatusEvent(
                event.transactionId(),
                event.status(),
                event.clearedAt(),
                event.detail());
        sseEmitterService.resolve(event.transactionId(), sseEvent);
    } catch (Exception ex) {
        // ... existing error handling unchanged ...
    }
}
```

### Layer 3: payment-gateway — new transactions history endpoint

**File (new):** `apps/payment-gateway/src/main/java/com/showcase/gateway/controller/TransactionHistoryController.java`

```java
package com.showcase.gateway.controller;

import com.showcase.gateway.dto.TransactionSummary;
import com.showcase.gateway.service.TransactionHistoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionHistoryController {

    private final TransactionHistoryService historyService;

    public TransactionHistoryController(TransactionHistoryService historyService) {
        this.historyService = historyService;
    }

    /**
     * GET /api/v1/transactions/recent?limit=10
     * Returns the N most-recent completed transactions.
     * Requires a valid JWT (any authenticated user).
     */
    @GetMapping("/recent")
    public ResponseEntity<List<TransactionSummary>> getRecent(
            @RequestParam(defaultValue = "10") int limit) {
        int safeLimit = Math.min(limit, 50);
        return ResponseEntity.ok(historyService.getRecent(safeLimit));
    }
}
```

**File:** `apps/payment-gateway/src/main/java/com/showcase/gateway/config/SecurityConfig.java`

Add before `.anyRequest().authenticated()`:

```java
.requestMatchers(HttpMethod.GET, "/api/v1/transactions/recent").authenticated()
```

**File:** `apps/payment-gateway/src/main/resources/application.yml`

Add under the `app:` block:

```yaml
app:
  history:
    max-size: ${APP_HISTORY_MAX_SIZE:50}
  kafka:
    # ... existing kafka config ...
```

### Layer 4: SPA — transactions API helper

**File:** `apps/spa-mobile-app/src/api/payments.js`

Add a new exported function alongside `initiatePayment` and `getPaymentStreamUrl`:

```js
export async function fetchRecentTransactions(limit = 10) {
  const { getToken } = useKeycloak()
  const res = await fetch(
    `${config.apiBaseUrl}/api/v1/transactions/recent?limit=${limit}`,
    { headers: { 'Authorization': `Bearer ${getToken()}` } }
  )
  if (!res.ok) throw new Error(`Transactions fetch failed: HTTP ${res.status}`)
  return res.json()  // TransactionSummary[]
}
```

### Layer 5: SPA — new useTransactions composable

**File (new):** `apps/spa-mobile-app/src/composables/useTransactions.js`

```js
import { ref, onMounted } from 'vue'
import { fetchRecentTransactions } from '../api/payments.js'

export function useTransactions() {
  const transactions = ref([])
  const txLoading = ref(true)
  const txError = ref('')

  async function loadTransactions() {
    txLoading.value = true
    txError.value = ''
    try {
      transactions.value = await fetchRecentTransactions(10)
    } catch (e) {
      txError.value = 'Could not load transactions'
    } finally {
      txLoading.value = false
    }
  }

  onMounted(loadTransactions)

  return { transactions, txLoading, txError, loadTransactions }
}
```

### Layer 6: SPA — DashboardView update

**File:** `apps/spa-mobile-app/src/views/DashboardView.vue`

Add to `<script setup>`:

```js
import { useTransactions } from '../composables/useTransactions.js'
const { transactions, txLoading, txError, loadTransactions } = useTransactions()
```

If `onActivated` is already added for balance refresh (R28), also call `loadTransactions`
there:

```js
onActivated(() => { loadBalance(); loadTransactions() })
```

Replace the static "Recent Activity" section template with a dynamic list:

```html
<div class="section recent-section">
  <h3 class="section-title">Recent Activity</h3>

  <p v-if="txLoading" class="empty-hint">Loading…</p>
  <p v-else-if="txError" class="empty-hint">{{ txError }}</p>

  <div v-else-if="transactions.length === 0" class="empty-state">
    <!-- existing empty-state SVG and text unchanged -->
  </div>

  <ul v-else class="tx-list">
    <li v-for="tx in transactions" :key="tx.transactionId" class="tx-item">
      <div class="tx-meta">
        <span class="tx-id">{{ tx.transactionId.slice(0, 8) }}…</span>
        <span :class="['tx-status', tx.status === 'COMPLETED' ? 'status-ok' : 'status-fail']">
          {{ tx.status }}
        </span>
      </div>
      <div class="tx-route">{{ tx.sourceAccount }} → {{ tx.destinationAccount }}</div>
      <div class="tx-footer">
        <span class="tx-amount">
          {{ new Intl.NumberFormat('en-US', { style: 'currency', currency: tx.currency || 'USD' })
               .format(tx.amount) }}
        </span>
        <span class="tx-time">{{ tx.clearedAt ? new Date(tx.clearedAt).toLocaleString() : '' }}</span>
      </div>
    </li>
  </ul>
</div>
```

Add `<style scoped>` entries for the new classes:

```css
.tx-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.tx-item {
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 0.75rem 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.tx-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.tx-id {
  font-size: 0.75rem;
  color: var(--color-text-secondary);
  font-family: monospace;
}

.tx-status {
  font-size: 0.7rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  padding: 0.15rem 0.4rem;
  border-radius: 4px;
}

.status-ok   { background: #d4f4e2; color: #1a7a40; }
.status-fail { background: #fde8e8; color: #c00000; }

.tx-route {
  font-size: 0.85rem;
  color: var(--color-text);
  font-weight: 500;
}

.tx-footer {
  display: flex;
  justify-content: space-between;
  font-size: 0.8rem;
  color: var(--color-text-secondary);
}

.tx-amount {
  font-weight: 600;
  color: var(--color-text);
}
```

**Real-time refresh: hooking into the SSE completion event**

The payment status page (`/payments/:txId/status`) already listens to the SSE stream.
When the SSE event arrives with a final status, that view navigates back to `/dashboard`
(or shows a result). At that navigation moment, `onMounted` or `onActivated` in
`DashboardView.vue` re-fetches transactions. No polling is needed. The data will already
be in payment-gateway's in-memory store because `PaymentCompletedListener` populates it
before resolving the SSE emitter.

If the payment status view does not navigate automatically, the SPA developer should
trigger `router.push('/dashboard')` on SSE completion. That is outside this task's scope
but is noted for completeness.

---

## New files

- `apps/payment-gateway/src/main/java/com/showcase/gateway/dto/PendingPaymentRecord.java` — transient record stored on payment initiation
- `apps/payment-gateway/src/main/java/com/showcase/gateway/dto/TransactionSummary.java` — JSON response record for completed transactions
- `apps/payment-gateway/src/main/java/com/showcase/gateway/service/TransactionHistoryService.java` — in-memory pending registry and completed-transaction deque
- `apps/payment-gateway/src/main/java/com/showcase/gateway/controller/TransactionHistoryController.java` — REST endpoint `GET /api/v1/transactions/recent`
- `apps/spa-mobile-app/src/composables/useTransactions.js` — reactive transaction list composable

## Modified files

- `apps/payment-gateway/src/main/java/com/showcase/gateway/service/PaymentServiceImpl.java` — inject `TransactionHistoryService`, call `registerPending` after generating `transactionId`
- `apps/payment-gateway/src/main/java/com/showcase/gateway/messaging/PaymentCompletedListener.java` — inject `TransactionHistoryService`, call `complete` on Kafka event receipt
- `apps/payment-gateway/src/main/java/com/showcase/gateway/config/SecurityConfig.java` — permit `GET /api/v1/transactions/recent` for authenticated users
- `apps/payment-gateway/src/main/resources/application.yml` — add `app.history.max-size` property
- `apps/spa-mobile-app/src/api/payments.js` — add `fetchRecentTransactions` export
- `apps/spa-mobile-app/src/views/DashboardView.vue` — replace static empty state with dynamic transaction list

---

## Acceptance criteria

- `GET /api/v1/transactions/recent` without a JWT returns HTTP 401.
- `GET /api/v1/transactions/recent` with a valid JWT and an empty history (fresh gateway
  start, no payments yet) returns HTTP 200 with an empty JSON array `[]`.
- After completing one payment, `GET /api/v1/transactions/recent` returns a JSON array
  with exactly one entry containing the correct `transactionId`, `sourceAccount`,
  `destinationAccount`, `amount`, `currency`, `status` (`"COMPLETED"`), and a non-null
  `clearedAt`.
- `GET /api/v1/transactions/recent?limit=5` returns at most 5 entries even if more are
  stored.
- Dashboard "Recent Activity" section renders the list (not the empty state) after at
  least one payment completes.
- Each list item displays: truncated `transactionId` (first 8 chars + `…`),
  `COMPLETED` or `FAILED` status badge, `sourceAccount → destinationAccount` route,
  formatted amount with currency symbol, and a human-readable timestamp.
- Navigating away from `/dashboard` and back after a new payment shows the new transaction
  at the top of the list without a manual page reload.
- The in-memory store respects the 50-entry cap: a 51st completed payment evicts the
  oldest entry.
- `./mvnw test -pl apps/payment-gateway` passes with new unit tests for:
  - `TransactionHistoryService` covering `registerPending` + `complete` + `getRecent`
    and the 50-entry eviction behaviour.
  - `TransactionHistoryController` covering the 200 response with mocked service and the
    `limit` capping to 50.
  - Updated `PaymentCompletedListenerTest` verifying that `transactionHistoryService.complete()`
    is called with correct arguments.
  - Updated `PaymentServiceImplTest` verifying that `transactionHistoryService.registerPending()`
    is called with the correct `PendingPaymentRecord`.
