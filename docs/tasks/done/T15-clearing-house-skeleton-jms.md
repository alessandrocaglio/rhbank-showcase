# T15 · clearing-house: Project Skeleton + IBM MQ CDI Config + JMS Consumer

**Phase:** 5 — clearing-house (Quarkus 3.15.x)
**Status:** todo
**Depends on:** T01

## Deliverables
- `apps/clearing-house/pom.xml` — Quarkus 3.15.1, extensions: `quarkus-smallrye-reactive-messaging-kafka`, `quarkus-opentelemetry`, `quarkus-smallrye-health`, `quarkus-arc`; direct dependency on `com.ibm.mq:com.ibm.mq.allclient:9.3.5.1`, `javax.jms:javax.jms-api:2.0.1`
- `src/main/resources/application.properties` — all values from env vars with dev defaults (IBM MQ host/port/QM/channel/queue, Kafka bootstrap)
- `MqConnectionFactoryProducer` (`@ApplicationScoped`) — reads env vars, creates and exposes `MQConnectionFactory` as CDI bean
- `ClearingResult` record — `transactionId`, `status` (enum: COMPLETED/FAILED), `detail`, `clearedAt`
- `ClearingService` (`@ApplicationScoped`) — `process(String transactionId) → ClearingResult`:
  - Simulates clearing: `ThreadLocalRandom` delay 100–500ms, 95% COMPLETED / 5% FAILED
- `MqMessageListener` (`@ApplicationScoped`, `@PostConstruct` starts listener thread):
  - Creates `JMSContext` from injected `MQConnectionFactory`
  - `MessageListener` on queue `DEV.QUEUE.CLEARING`
  - Reads `traceparent` JMS String property → restores OTel context (per TRACING.md Boundary 3)
  - Sets OTel span attributes: `bank.payment.transaction_id`, `bank.clearing.status`
  - Delegates to `ClearingService`
  - Passes result to `PaymentCompletedPublisher` (stub interface for now)
- `PaymentCompletedPublisher` interface — `void publish(ClearingResult result)`
- `ClearingHouseApplication` main class (if needed by Quarkus)

## Unit Tests
`MqConnectionFactoryProducerTest` (`@ExtendWith(MockitoExtension.class)`, config via system properties):
- Verify `MQConnectionFactory` created with correct host, port, queue manager, channel

`ClearingServiceTest` (plain JUnit 5, no framework):
- Call `process("txn-001")` 100 times → count COMPLETED in range [80, 100] (statistical, ≥95% target)
- FAILED result → `detail` is non-null and non-blank

`MqMessageListenerTest` (`@ExtendWith(MockitoExtension.class)`):
- Mock `TextMessage` with `traceparent = "00-abc-def-01"` and `transactionId = "txn-001"`
- Mock `ClearingService` returning COMPLETED result
- Assert `ClearingService.process("txn-001")` called once
- Assert `PaymentCompletedPublisher.publish()` called once with matching result

## Verification
```bash
./mvnw test -pl apps/clearing-house
./mvnw package -pl apps/clearing-house -DskipTests
# All 3 test classes pass
# JaCoCo line coverage ≥ 80%
```

## Acceptance Criteria
- [ ] `MqConnectionFactoryProducerTest` — factory config verified
- [ ] `ClearingServiceTest` — statistical range [80,100] passes reliably
- [ ] `MqMessageListenerTest` — service call and publisher call both verified
- [ ] `./mvnw package -DskipTests` exits 0
- [ ] JaCoCo line coverage ≥ 80%
