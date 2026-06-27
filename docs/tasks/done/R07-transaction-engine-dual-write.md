# R07 — 🔴 transaction-engine: ledger committed before MQ send, no rollback on MQ failure

## Problem
`PaymentApprovedListener.java`:
```java
TransactionLedger saved = ledgerService.persistLedgerRecord(event);  // commits to DB
mqPublishingService.publishToClearingQueue(saved);                     // outside any DB tx
```
The DB write commits in its own implicit transaction before the MQ send. If the IBM MQ send
fails (broker unreachable, channel error), the ledger record is permanently `PENDING` and no
clearing message reaches the queue. The payment is stuck with no recovery path.

## Files to change
- `apps/transaction-engine/src/main/java/com/showcase/engine/messaging/PaymentApprovedListener.java`
- `apps/transaction-engine/src/main/java/com/showcase/engine/service/MqPublishingServiceImpl.java`

## Fix options
**Option A (minimal):** Wrap the MQ send in `@Retryable` (Spring Retry) with exponential back-off.
Log clearly if all retries fail. This doesn't solve the atomicity problem but limits the window.

**Option B (correct):** Use the outbox pattern — write an `outbox_messages` table row in the same
DB transaction as the ledger record. A separate `@Scheduled` task polls the outbox, sends to MQ,
and marks the row as sent.

## Acceptance (Option A minimum)
- Transient MQ unavailability retries automatically and eventually delivers
- Persistent MQ failure produces a clear ERROR log and a DLT entry
