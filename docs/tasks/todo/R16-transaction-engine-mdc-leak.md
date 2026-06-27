# R16 — 🔴 transaction-engine: MDC `transactionId` leaks across Kafka consumer threads

## Problem
`LedgerService.java:22`: `MDC.put("transactionId", ...)` is called with no corresponding
`MDC.remove()` in a `finally` block. Spring Kafka reuses consumer threads — MDC state from
message N bleeds into message N+1 on the same thread, corrupting log correlation. Every log
line after the first payment on a given thread carries the wrong `transactionId`.

## File to change
- `apps/transaction-engine/src/main/java/com/showcase/engine/service/LedgerService.java`

## Fix
Wrap the MDC lifecycle in a try/finally:

```java
MDC.put("transactionId", event.transactionId());
try {
    // ... all service logic ...
    return savedLedger;
} finally {
    MDC.remove("transactionId");
}
```

Or use `MDC.putCloseable()` with try-with-resources (available in SLF4J 2.x):
```java
try (var ignored = MDC.putCloseable("transactionId", event.transactionId())) {
    // ... logic ...
}
```

## Acceptance
- Processing two sequential payments on the same Kafka consumer thread: each payment's log lines
  carry only its own `transactionId`, not the previous one's
