# R03 — 🔴 account-verifier: balance debited even if Kafka publish fails (dual-write)

## Problem
`AccountVerificationService.java` is `@Transactional`. Inside it, `publishApproved()` is annotated
`@Transactional(TxType.NOT_SUPPORTED)` — this **suspends** the outer JTA transaction before
`emitter.send()` is called. The `emitter.send()` return value (a `CompletionStage<Void>`) is
discarded; asynchronous Kafka failures are silently swallowed.

Result: the JTA transaction commits (balance debited in DB), Kafka message is never delivered,
payment pipeline stalls with no alert. Money deducted, payment never processed.

## Files to change
- `apps/account-verifier/src/main/java/com/showcase/verifier/service/AccountVerificationService.java`
- `apps/account-verifier/src/main/java/com/showcase/verifier/service/PaymentEventPublisherImpl.java`
- `apps/account-verifier/src/test/java/com/showcase/verifier/service/AccountVerificationServiceTest.java`

## Fix options (pick one)
**Option A (minimal):** Await the `CompletionStage` in `publishApproved()` and propagate failures;
keep `TxType.NOT_SUPPORTED` so the DB commit happens first, but at least log/alert on Kafka failure.

**Option B (correct):** Remove the Kafka emit from the service entirely. Write an outbox record inside
the same JTA transaction. A separate poller/CDI observer reads the outbox and publishes to Kafka.

## Acceptance
- Simulated Kafka broker outage (stop Redpanda) → balance is not permanently debited without a
  corresponding Kafka message (Option B), or a clear error/DLT entry is produced (Option A)
- Existing tests still pass
