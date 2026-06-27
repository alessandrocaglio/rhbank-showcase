# R24 — 🟡 transaction-engine: `traceId` not stored in ledger DB record

## Problem
The `transaction_ledger` table has no `trace_id` column. For a showcase whose primary objective
is demonstrating end-to-end distributed tracing, the inability to JOIN a Grafana Tempo trace to
its corresponding DB row (or vice versa) is a missed demonstration opportunity. You must correlate
via the span's attribute alone, which requires Tempo to be running — you cannot cross-reference
from a DB admin query.

## Files to change
- `apps/transaction-engine/src/main/resources/db/migration/V2__add_trace_id.sql` (new migration)
- `apps/transaction-engine/src/main/java/com/showcase/engine/domain/TransactionLedger.java`
- `apps/transaction-engine/src/main/java/com/showcase/engine/service/LedgerService.java`

## Fix
```sql
-- V2__add_trace_id.sql
ALTER TABLE transaction_ledger ADD COLUMN trace_id VARCHAR(32);
```

```java
// TransactionLedger.java — add field
private String traceId;

// LedgerService.java — populate when persisting
String traceId = Span.current().getSpanContext().getTraceId();
ledger.setTraceId(traceId);
```

## Acceptance
- After submitting a payment, querying `SELECT trace_id FROM transaction_ledger WHERE transaction_id = '...'`
  returns the trace ID
- Pasting that trace ID into Grafana Tempo shows the full end-to-end trace
