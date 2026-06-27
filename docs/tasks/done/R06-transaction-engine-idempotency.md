# R06 — 🔴 transaction-engine: Kafka redeliver causes PK violation, payment stuck forever

## Problem
`PaymentApprovedListener.java` — if Kafka redelivers `payment-approved` (consumer restart, rebalance,
at-least-once semantics), `repository.save(ledger)` attempts an INSERT with a duplicate `transactionId`
PK → `DataIntegrityViolationException`. This triggers the DefaultErrorHandler retry loop (3 more
failures), then routes the record to the DLT. The clearing message is **never sent** for the
redelivered event — the payment is permanently stuck.

## Files to change
- `apps/transaction-engine/src/main/java/com/showcase/engine/messaging/PaymentApprovedListener.java`
- `apps/transaction-engine/src/main/java/com/showcase/engine/service/LedgerService.java`
- `apps/transaction-engine/src/test/java/com/showcase/engine/messaging/PaymentApprovedListenerTest.java`

## Fix
Before calling `repository.save()`, check if a record already exists:

```java
if (ledgerRepository.existsByTransactionId(event.transactionId())) {
    log.warn("Duplicate payment-approved for txId={}, skipping", event.transactionId());
    return;
}
```

If a record exists but the MQ message was never sent (previous pod crash after DB write), re-send
the MQ message using the stored ledger record.

## Acceptance
- Sending the same `payment-approved` Kafka message twice produces exactly one ledger record and
  exactly one MQ clearing message
- Tests pass; add a test for the duplicate-event scenario
