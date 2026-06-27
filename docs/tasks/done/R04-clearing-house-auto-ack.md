# R04 — 🔴 clearing-house: AUTO_ACKNOWLEDGE drops MQ messages if processing fails

## Problem
`MqMessageListener.listenLoop()` opens the JMSContext with no session mode, defaulting to
`AUTO_ACKNOWLEDGE`. The JMS spec acknowledges the message to the broker the instant
`consumer.receive()` returns — **before** `processMessage()` is called. If processing fails
(Kafka publish throws, JVM crashes mid-flight), the message is already gone from the queue
with no re-delivery.

## File to change
- `apps/clearing-house/src/main/java/com/showcase/clearing/messaging/MqMessageListener.java`

## Fix
Change `connectionFactory.createContext()` to `connectionFactory.createContext(JMSContext.CLIENT_ACKNOWLEDGE)`.
Call `message.acknowledge()` inside `processMessage()` **after** `publisher.publish(result)` succeeds.
On failure, do not acknowledge — the broker will redeliver after connection close/session recovery.

```java
try (JMSContext context = connectionFactory.createContext(JMSContext.CLIENT_ACKNOWLEDGE)) {
    ...
    // inside processMessage, after successful publish:
    message.acknowledge();
}
```

## Acceptance
- Simulated Kafka failure during clearing → message remains on `DEV.QUEUE.CLEARING` and is
  redelivered after recovery
- Tests still pass (MqMessageListenerTest)
