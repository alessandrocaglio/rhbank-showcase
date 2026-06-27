# R05 — 🔴 clearing-house: non-JMS exception permanently kills MQ listener thread

## Problem
`MqMessageListener.listenLoop()` outer catch at line 88 only catches `JMSRuntimeException`.
A `RuntimeException` from `publisher.publish()` (e.g. Kafka back-pressure overflow) is not a
`JMSRuntimeException` — it propagates uncaught out of the inner `while` loop and terminates the
listener thread permanently. **One Kafka broker blip → all subsequent MQ messages are unprocessed
until the pod restarts.** No alert, no liveness probe detects this.

## File to change
- `apps/clearing-house/src/main/java/com/showcase/clearing/messaging/MqMessageListener.java`

## Fix
Broaden the outer catch to `Exception` (or at minimum catch `RuntimeException`), apply the same
5-second sleep-and-retry, and add a log/metric so the failure is observable:

```java
} catch (Exception e) {
    LOG.errorf(e, "Listener error (non-JMS), retrying in 5s");
    try { Thread.sleep(5_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
}
```

Also add a `@PreDestroy` shutdown hook that sets a `volatile boolean running = false` and interrupts
the thread for clean shutdown.

## Acceptance
- Simulated Kafka timeout during publish → listener logs the error, sleeps, and resumes processing
  the next MQ message without a pod restart
