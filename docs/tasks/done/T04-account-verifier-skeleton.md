# T04 · account-verifier: Project Skeleton + Domain Model + Flyway Migration

**Phase:** 2 — account-verifier (Quarkus 3.15.x)
**Status:** todo
**Depends on:** T01

## Deliverables
- `apps/account-verifier/pom.xml` — Quarkus 3.15.1, extensions: `quarkus-grpc`, `quarkus-hibernate-orm-panache`, `quarkus-jdbc-postgresql`, `quarkus-flyway`, `quarkus-smallrye-reactive-messaging-kafka`, `quarkus-opentelemetry`, `quarkus-smallrye-health`; depends on `apps/grpc-api`
- `src/main/resources/application.properties` — all values from env vars with dev defaults (DB URL, credentials, gRPC port 9090, HTTP port 8080)
- `Account` entity (`@Entity`) — fields: `accountId`, `customerName`, `balance`, `status` — package `com.showcase.verifier.domain`
- `AccountRepository` (`PanacheRepository<Account, String>`) — package `com.showcase.verifier.repository`
- `src/main/resources/db/migration/V1__init_accounts.sql` — schema + 4 seed rows (ACC-001…ACC-004)

## Unit Tests
`AccountRepositoryTest` (`@QuarkusTest`, `@TestTransaction`):
- Find existing account by ID → found with correct balance
- Find non-existent account → empty Optional / null
- Verify seed data loaded (status = ACTIVE for ACC-001)

## Verification
```bash
./mvnw test -pl apps/account-verifier
# AccountRepositoryTest passes
# JaCoCo coverage ≥ 80% reported in target/site/jacoco/
```

## Acceptance Criteria
- [ ] `./mvnw package -pl apps/account-verifier -DskipTests` exits 0
- [ ] `./mvnw test -pl apps/account-verifier` exits 0
- [ ] `AccountRepositoryTest` — all 3 assertions pass
- [ ] JaCoCo line coverage ≥ 80%
