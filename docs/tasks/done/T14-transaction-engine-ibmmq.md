# T14 · transaction-engine: IBM MQ JMS Producer + Trace Propagation

**Phase:** 4 — transaction-engine (Spring Boot 3.3.x)
**Status:** todo
**Depends on:** T13

## Deliverables
- `ClearingMessagePayload` record — `transactionId`, `sourceAccount`, `destinationAccount`, `amount`, `currency`
- `MqPublishingServiceImpl` (implements `MqPublishingService`, `@Service`):
  - Uses `JmsTemplate` (auto-configured by `mq-jms-spring-boot-starter`)
  - Sends to queue `DEV.QUEUE.CLEARING`
  - Inside `MessageCreator` lambda, explicitly sets:
    - `message.setStringProperty("traceparent", <current W3C traceparent>)` — extracted from OTel `Context.current()` via `GlobalOpenTelemetry.getPropagators()`
    - `message.setStringProperty("transactionId", transactionId)`
  - Message body: JSON-serialised `ClearingMessagePayload`
- Wire `MqPublishingServiceImpl` into `PaymentApprovedListener` (replaces stub)

## Unit Tests
`MqPublishingServiceTest` (`@ExtendWith(MockitoExtension.class)`):
- Mock `JmsTemplate`; capture the `MessageCreator` lambda via `ArgumentCaptor`
- Execute the captured lambda with a mock `Session` and `TextMessage`
- Assert `traceparent` property set to a non-empty string
- Assert `transactionId` property matches the input
- Assert message body is valid JSON containing `transactionId` and `amount`

## Verification
```bash
./mvnw test -pl apps/transaction-engine
# MqPublishingServiceTest — all 3 assertions pass
# Full suite (T12–T14): all tests pass
# JaCoCo line coverage ≥ 80%
```

## Acceptance Criteria
- [ ] `traceparent` JMS property explicitly set (critical for trace continuity across JMS boundary)
- [ ] `transactionId` JMS property set
- [ ] Message body is valid JSON
- [ ] Full `transaction-engine` test suite green
- [ ] JaCoCo line coverage ≥ 80%
