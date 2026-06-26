# T16 · clearing-house: Kafka Producer (payment-completed)

**Phase:** 5 — clearing-house (Quarkus 3.15.x)
**Status:** todo
**Depends on:** T15

## Deliverables
- `PaymentCompletedEvent` record — `transactionId`, `status` (String: "COMPLETED"/"FAILED"), `clearedAt`, `detail`; must match `PaymentCompletedEvent` record in `payment-gateway`
- `PaymentCompletedPublisherImpl` (implements `PaymentCompletedPublisher`, `@ApplicationScoped`):
  - SmallRye `@Channel("payment-completed")` `MutinyEmitter<PaymentCompletedEvent>`
  - Maps `ClearingResult` → `PaymentCompletedEvent`
  - Emits to channel
- Wire `PaymentCompletedPublisherImpl` into `MqMessageListener` (replaces stub)
- `application.properties` — SmallRye channel config for `payment-completed` (Kafka connector) and dead-letter channel `payment-completed.DLT`

## Unit Tests
`PaymentCompletedPublisherTest` (`@QuarkusTest`, SmallRye `InMemoryConnector`):
- Publish COMPLETED `ClearingResult` → assert message on channel has `status = "COMPLETED"`, `transactionId` matches, `clearedAt` non-null
- Publish FAILED `ClearingResult` → assert `status = "FAILED"`, `detail` non-null

## Verification
```bash
./mvnw test -pl apps/clearing-house
# PaymentCompletedPublisherTest — both cases pass
# Full suite (T15 + T16): all tests pass
# JaCoCo line coverage ≥ 80%
```

## Acceptance Criteria
- [ ] `PaymentCompletedPublisherTest` — COMPLETED case passes
- [ ] `PaymentCompletedPublisherTest` — FAILED case passes
- [ ] Dead-letter channel configured in `application.properties`
- [ ] Full `clearing-house` test suite green
- [ ] JaCoCo line coverage ≥ 80%
