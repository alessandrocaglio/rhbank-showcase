# T06 · account-verifier: Kafka Producer

**Phase:** 2 — account-verifier (Quarkus 3.15.x)
**Status:** todo
**Depends on:** T05

## Deliverables
- `PaymentApprovedEvent` record — fields: `transactionId`, `sourceAccount`, `destinationAccount`, `amount`, `currency`, `approvedAt`
- `PaymentEventPublisher` (`@ApplicationScoped`) — SmallRye `@Channel("payment-approved")` `MutinyEmitter`
- Wire publisher call into `AccountVerificationService`: emit after successful DB transaction commit
- `application.properties` — SmallRye channel config for `payment-approved` (Kafka connector, topic name) and dead-letter channel `payment-approved.DLT`

## Unit Tests
`PaymentEventPublisherTest` (`@QuarkusTest`, SmallRye `InMemoryConnector`):
- Emit event → assert message received on in-memory channel with correct `transactionId`, `sourceAccount`, `amount`

`AccountVerificationServiceTest` (extend from T05):
- Approved path → publisher called once with correct payload
- Rejected path → publisher never called

## Verification
```bash
./mvnw test -pl apps/account-verifier
# All tests pass (T04 + T05 + T06 suites)
# JaCoCo line coverage ≥ 80%
```

## Acceptance Criteria
- [ ] `PaymentEventPublisherTest` — message fields asserted on in-memory channel
- [ ] `AccountVerificationServiceTest` — publisher call/no-call verified via mock
- [ ] Dead-letter channel configured in `application.properties`
- [ ] JaCoCo line coverage ≥ 80%
