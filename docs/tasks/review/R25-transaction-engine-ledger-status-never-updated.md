# R25 — 🟡 transaction-engine: ledger status always "PENDING", never updated to CLEARED/FAILED

## Problem
`transaction_ledger.status` is written as `"PENDING"` when the record is created and never
updated. After the clearing house processes a payment and publishes `payment-completed`, the
ledger still shows `PENDING`. The immutable ledger serves no useful audit function — every row
ever written reports the same status regardless of outcome.

## Options

**Option A (minimal — append-only):** The ledger is intentionally immutable. Add a separate
`ledger_events` table that records state transitions:
```sql
CREATE TABLE ledger_events (
  id SERIAL PRIMARY KEY,
  transaction_id VARCHAR(50) REFERENCES transaction_ledger(transaction_id),
  status VARCHAR(20) NOT NULL,
  recorded_at TIMESTAMP WITH TIME ZONE DEFAULT now()
);
```
`payment-gateway` (which consumes `payment-completed`) inserts a row here on receipt.

**Option B (simplest):** Have `payment-gateway`'s `PaymentCompletedListener` call a REST endpoint
on `transaction-engine` to update the ledger status. This requires adding an HTTP server to
`transaction-engine` (or using Kafka for the return trip).

**Option C (current behaviour — document it):** Accept that the ledger is append-only at creation,
update CLAUDE.md / README to note this is a showcase simplification.

## Files affected (Option A)
- New migration `V3__ledger_events.sql`
- New entity + repository in `transaction-engine`
- `PaymentCompletedListener` in `payment-gateway` (write the event row)

## Acceptance
- After `./showcase.sh smoke`, querying the ledger shows the terminal status (CLEARED or FAILED)
  for the processed payment
