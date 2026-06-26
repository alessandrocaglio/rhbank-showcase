# T07 · payment-gateway: Project Skeleton + JWT Security Configuration

**Phase:** 3 — payment-gateway (Spring Boot 3.3.x)
**Status:** todo
**Depends on:** T01

## Deliverables
- `apps/payment-gateway/pom.xml` — Spring Boot 3.3.5, dependencies: `spring-boot-starter-web`, `spring-boot-starter-oauth2-resource-server`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`, `spring-kafka`, `net.devh:grpc-client-spring-boot-starter:3.1.0.RELEASE`, `io.opentelemetry:opentelemetry-api`; depends on `apps/grpc-api`
- `src/main/resources/application.yml` — all values from env vars with dev defaults
- `SecurityConfig` (`@Configuration`) — stateless JWT resource server:
  - `POST /api/v1/payments` requires `payment-init` role
  - `GET /api/v1/payments/stream/**` requires authentication
  - `GET /actuator/health` — permit all
  - Session: STATELESS
- `PaymentGatewayApplication.java`

## Unit Tests
`SecurityConfigTest` (`@WebMvcTest(controllers = {})`, `@Import(SecurityConfig.class)`):
- `GET /actuator/health` without token → 200
- `POST /api/v1/payments` without token → 401
- `POST /api/v1/payments` with valid JWT but missing role → 403

## Verification
```bash
./mvnw test -pl apps/payment-gateway
./mvnw package -pl apps/payment-gateway -DskipTests   # fat JAR builds
```

## Acceptance Criteria
- [ ] `SecurityConfigTest` — all 3 assertions pass
- [ ] `./mvnw package -DskipTests` exits 0
- [ ] JaCoCo line coverage ≥ 80%
