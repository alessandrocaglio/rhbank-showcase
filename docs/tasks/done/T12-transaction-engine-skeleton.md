# T12 · transaction-engine: Project Skeleton + Domain Model + Flyway Migration

**Phase:** 4 — transaction-engine (Spring Boot 3.3.x)
**Status:** todo
**Depends on:** T01

## Deliverables
- `apps/transaction-engine/pom.xml` — Spring Boot 3.3.5, dependencies: `spring-boot-starter-data-jpa`, `spring-boot-starter-actuator`, `spring-kafka`, `com.ibm.mq:mq-jms-spring-boot-starter:3.4.2`, `org.flywaydb:flyway-core`, `org.postgresql:postgresql`, `io.opentelemetry:opentelemetry-api`
- `src/main/resources/application.yml` — all values from env vars with dev defaults (DB, Kafka, IBM MQ)
- `TransactionLedger` entity (`@Entity`, `@Table(name = "transaction_ledger")`) — package `com.showcase.engine.domain`:
  - `transactionId` (PK, `VARCHAR(50)`)
  - `sourceAccount`, `destinationAccount` (`VARCHAR(50)`)
  - `amount` (`NUMERIC(15,2)`)
  - `currency` (`VARCHAR(10)`, default `'USD'`)
  - `status` (`VARCHAR(20)`)
  - `createdAt` (`TIMESTAMP`, `@CreationTimestamp`)
- `TransactionLedgerRepository` (Spring Data JPA `JpaRepository<TransactionLedger, String>`)
- `src/main/resources/db/migration/V1__init_ledger.sql`
- `TransactionEngineApplication.java`

## Unit Tests
`TransactionLedgerRepositoryTest` (`@DataJpaTest`, H2 in-memory):
- Save a `TransactionLedger` → `findById()` returns it with all fields intact
- `createdAt` is set automatically (non-null after save)

## Verification
```bash
./mvnw test -pl apps/transaction-engine
./mvnw package -pl apps/transaction-engine -DskipTests
# TransactionLedgerRepositoryTest passes
```

## Acceptance Criteria
- [ ] `TransactionLedgerRepositoryTest` — both assertions pass
- [ ] `./mvnw package -DskipTests` exits 0
- [ ] JaCoCo line coverage ≥ 80%
