# T11 · payment-gateway: Kafka Consumer (payment-completed)

**Phase:** 3 — payment-gateway (Spring Boot 3.3.x)
**Status:** todo
**Depends on:** T10

## Deliverables
- `PaymentCompletedEvent` record — `transactionId`, `status`, `clearedAt`, `detail`
- `PaymentCompletedListener` (`@Component`):
  - `@KafkaListener(topics = "payment-completed", groupId = "${spring.kafka.consumer.group-id}")`
  - Deserializes JSON payload → `PaymentCompletedEvent`
  - Calls `sseEmitterService.resolve(event.transactionId(), toStatusEvent(event))`
- `KafkaConfig` (`@Configuration`):
  - `DefaultErrorHandler` with `ExponentialBackOff(1000L, 2.0)`, max 3 attempts
  - `DeadLetterPublishingRecoverer` routing to `payment-completed.DLT`

## Unit Tests
`PaymentCompletedListenerTest` (`@SpringBootTest`, `@EmbeddedKafka(topics = {"payment-completed"})`):
- Send valid JSON message → assert `sseEmitterService.resolve()` called with matching `transactionId` (Mockito `@MockBean`)
- Send malformed JSON → assert `sseEmitterService.resolve()` never called; message routed to DLT after retries (verify via `@SpyBean KafkaTemplate`)

## Verification
```bash
./mvnw test -pl apps/payment-gateway
# PaymentCompletedListenerTest — both cases pass
# Full suite (T07–T11): all tests pass
# JaCoCo line coverage ≥ 80%
```

## Acceptance Criteria
- [ ] `PaymentCompletedListenerTest` — valid-message case passes
- [ ] `PaymentCompletedListenerTest` — malformed-message routed to DLT (no infinite retry)
- [ ] Full `payment-gateway` test suite green
- [ ] JaCoCo line coverage ≥ 80%
