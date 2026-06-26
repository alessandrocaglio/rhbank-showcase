# T13 · transaction-engine: Kafka Consumer + Ledger Persistence

**Phase:** 4 — transaction-engine (Spring Boot 3.3.x)
**Status:** todo
**Depends on:** T12

## Deliverables
- `PaymentApprovedEvent` record — `transactionId`, `sourceAccount`, `destinationAccount`, `amount`, `currency`, `approvedAt` (must match account-verifier's output schema)
- `LedgerService` (`@Service`):
  - `record(PaymentApprovedEvent)` — maps event → `TransactionLedger`, persists via repository, sets OTel span attributes: `bank.payment.transaction_id`, `bank.ledger.record_id`
- `MqPublishingService` interface — `void publish(TransactionLedger record)` (stub — implemented in T14)
- `PaymentApprovedListener` (`@Component`, `@KafkaListener(topics = "payment-approved")`):
  - Deserializes event, calls `LedgerService.record()`, calls `MqPublishingService.publish()`
- `KafkaConfig` — `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` → `payment-approved.DLT`, 3 retries, exponential backoff 1s × 2

## Unit Tests
`LedgerServiceTest` (`@ExtendWith(MockitoExtension.class)`):
- Valid event → `repository.save()` called with entity matching all event fields
- `createdAt` set to non-null (use `@Captor`)

`PaymentApprovedListenerTest` (`@SpringBootTest`, `@EmbeddedKafka(topics = {"payment-approved"})`):
- Send valid JSON message → `LedgerService.record()` called (`@MockBean`)
- Send valid message → `MqPublishingService.publish()` called (`@MockBean`)

## Verification
```bash
./mvnw test -pl apps/transaction-engine
# LedgerServiceTest and PaymentApprovedListenerTest pass
# JaCoCo line coverage ≥ 80%
```

## Acceptance Criteria
- [ ] `LedgerServiceTest` — entity mapping verified via `@Captor`
- [ ] `PaymentApprovedListenerTest` — both downstream calls verified
- [ ] DLT configured in `KafkaConfig`
- [ ] JaCoCo line coverage ≥ 80%
