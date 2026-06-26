# CONVENTIONS.md — Code Conventions & Testing Standards

> These rules apply to **all code generated for this project**.
> When in doubt, match the style of the existing module rather than inventing something new.

---

## 1. Package Structure

### Spring Boot Services (`payment-gateway`, `transaction-engine`)

```
com.showcase.<service-name>/
├── config/          # Spring @Configuration classes, SecurityFilterChain, beans
├── controller/      # @RestController classes (REST endpoints, SSE)
├── service/         # Business logic (@Service)
├── repository/      # Spring Data JPA @Repository interfaces
├── domain/          # JPA @Entity classes
├── dto/             # Request/response DTOs (records preferred)
├── messaging/       # @KafkaListener, DeadLetterPublishingRecoverer config
├── client/          # gRPC stub wrappers (payment-gateway only)
└── <ServiceName>Application.java
```

**Package naming:**
- `payment-gateway` → `com.showcase.gateway`
- `transaction-engine` → `com.showcase.engine`

### Quarkus Services (`account-verifier`, `clearing-house`)

```
com.showcase.<service-name>/
├── config/          # @ApplicationScoped configuration beans (MQ factory, etc.)
├── grpc/            # @GrpcService implementation (account-verifier only)
├── service/         # Business logic @ApplicationScoped beans
├── repository/      # Panache repositories or plain @ApplicationScoped
├── domain/          # Hibernate @Entity (account-verifier only)
├── messaging/       # @Incoming / @Outgoing channel handlers
├── dto/             # Value objects (records preferred)
└── resource/        # @Path JAX-RS resource (if any HTTP endpoints needed)
```

**Package naming:**
- `account-verifier` → `com.showcase.verifier`
- `clearing-house` → `com.showcase.clearing`

---

## 2. Java Code Style

- **Java 21, JVM mode.** Do not use `--enable-preview` features.
- **Prefer records** for DTOs and value objects (immutable, no boilerplate).
- **No Lombok.** Use records, IDE-generated constructors, or plain Java.
- **No field injection** (`@Autowired` on fields). Use constructor injection in Spring. Use `@Inject` constructor injection in Quarkus.
- **Null safety:** Never return `null` from a service method — use `Optional<T>` or throw a domain exception.
- **No comments explaining what the code does.** Only comment the *why* when the reason is non-obvious (hidden constraint, workaround, subtle invariant).
- **Exception handling:** Catch specific exception types. Do not catch `Exception` unless you explicitly re-throw it with span recording (see [`TRACING.md`](TRACING.md)).
- **No hardcoded environment-specific strings.** Every Kafka topic name, IBM MQ queue name, service address, port, and credential **must** be externalised as an environment variable with a safe local default. A `private static final String` constant that holds a queue or topic name is a spec violation. Inject via `@Value("${...}")` (Spring) or `@ConfigProperty(name = "...")` (Quarkus) instead. The only acceptable use of a string literal for a destination name is as the `defaultValue` inside the property placeholder expression itself — e.g. `@Value("${app.mq.queues.clearing:DEV.QUEUE.CLEARING}")`.

### DTOs as Records

```java
// Request DTO
public record PaymentRequest(
    @NotBlank String sourceAccount,
    @NotBlank String destinationAccount,
    @Positive BigDecimal amount,
    @NotBlank String currency
) {}

// Response DTO
public record PaymentResponse(
    String transactionId,
    String status,
    String message
) {}
```

---

## 3. Configuration Files

### Spring Boot — `application.yml`

All Spring Boot services use **YAML** (`application.yml`), not `application.properties`. No hardcoded values — all runtime parameters must be configurable via environment variables using Spring's `${ENV_VAR:default}` syntax.

```yaml
spring:
  application:
    name: payment-gateway
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI:http://localhost:8080/realms/BankDemoRealm}
  kafka:
    bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
```

### Quarkus — `application.properties`

All Quarkus services use `application.properties`. Follow the same pattern — no hardcoded values.

```properties
quarkus.datasource.jdbc.url=${QUARKUS_DATASOURCE_JDBC_URL:jdbc:postgresql://localhost:5432/accounts_db}
quarkus.datasource.username=${QUARKUS_DATASOURCE_USERNAME:appuser}
quarkus.datasource.password=${QUARKUS_DATASOURCE_PASSWORD:apppassword}
```

---

## 4. Unit Testing Requirements

### Coverage Target

**≥ 80% line coverage** on all business logic classes. Coverage is enforced by JaCoCo configured in the root `pom.xml` with the `verify` lifecycle phase. Build **fails** if coverage drops below 80%.

### What Must Be Tested

| Layer | Test Type | Notes |
|---|---|---|
| Controllers / gRPC handlers | Slice test (mocked services) | Test HTTP status codes, request validation, error responses |
| Service / Business logic | Pure unit test | Mock all external dependencies |
| Repositories | Slice test (embedded DB) | Test query methods against real SQL |
| Kafka listeners | Unit test (mocked consumer) | Test message handling logic in isolation |
| JMS producers/consumers | Unit test (mocked JMS) | Test message construction and property setting |
| DTOs / Records | Unit test | Validate constraint annotations, mapping logic |

### What NOT to Test

- Framework wiring (Spring autoconfiguration, Quarkus CDI graph).
- Infrastructure startup (container health, DB connectivity).
- Trivial getters/setters (if any exist).

---

## 5. Spring Boot Testing Patterns

### Controller Tests (`@WebMvcTest`)

```java
@WebMvcTest(PaymentController.class)
@Import(SecurityTestConfig.class)      // Disable full security for unit tests
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PaymentService paymentService;

    @Test
    void shouldReturn202WhenPaymentIsValid() throws Exception {
        given(paymentService.initiatePayment(any())).willReturn(expectedResponse);

        mockMvc.perform(post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequestJson)
                .header("Authorization", "Bearer test-token"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.transactionId").isNotEmpty());
    }
}
```

### JPA Repository Tests (`@DataJpaTest`)

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)  // use H2
class TransactionLedgerRepositoryTest {

    @Autowired
    private TransactionLedgerRepository repository;

    @Test
    void shouldPersistLedgerRecord() {
        var record = new TransactionLedger(/* ... */);
        var saved = repository.save(record);
        assertThat(saved.getTransactionId()).isNotNull();
    }
}
```

### Service Tests (plain Mockito)

```java
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private AccountVerifierGrpcClient grpcClient;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @InjectMocks
    private PaymentService paymentService;

    @Test
    void shouldPublishToKafkaAfterSuccessfulVerification() {
        // given / when / then
    }
}
```

### Kafka Listener Tests

Use `@EmbeddedKafka` for Spring Kafka listener integration:

```java
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"payment-completed"})
class PaymentCompletedListenerTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockBean
    private SseEmitterService sseEmitterService;

    @Test
    void shouldResolveEmitterOnPaymentCompleted() throws Exception {
        kafkaTemplate.send("payment-completed", validPayload);
        // verify with Awaitility
        await().atMost(5, SECONDS)
               .untilAsserted(() -> verify(sseEmitterService).resolve(eq(transactionId), any()));
    }
}
```

---

## 6. Quarkus Testing Patterns

### Service Tests (`@QuarkusTest`)

```java
@QuarkusTest
class AccountVerificationServiceTest {

    @InjectMock
    AccountRepository accountRepository;

    @Inject
    AccountVerificationService service;

    @Test
    void shouldApprovePaymentWhenBalanceSufficient() {
        when(accountRepository.findById("ACC-001"))
            .thenReturn(new Account("ACC-001", "Alice", new BigDecimal("1000.00"), "ACTIVE"));

        var result = service.verify("ACC-001", new BigDecimal("500.00"));
        assertThat(result.approved()).isTrue();
    }
}
```

### gRPC Server Tests (`@QuarkusTest` with gRPC client)

```java
@QuarkusTest
class AccountServiceGrpcTest {

    @GrpcClient("account-verifier")
    AccountServiceGrpc.AccountServiceBlockingStub client;

    @InjectMock
    AccountVerificationService verificationService;

    @Test
    void shouldReturnApprovedForValidAccount() {
        when(verificationService.verify(any(), any())).thenReturn(VerificationResult.approved());

        var response = client.verifyAccount(VerifyAccountRequest.newBuilder()
            .setTransactionId("txn-001")
            .setSourceAccount("ACC-001")
            .setAmount(100.0)
            .build());

        assertThat(response.getApproved()).isTrue();
    }
}
```

### JaCoCo Configuration (in root `pom.xml`)

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.12</version>
    <executions>
        <execution>
            <id>prepare-agent</id>
            <goals><goal>prepare-agent</goal></goals>
        </execution>
        <execution>
            <id>check</id>
            <phase>verify</phase>
            <goals><goal>check</goal></goals>
            <configuration>
                <rules>
                    <rule>
                        <element>BUNDLE</element>
                        <limits>
                            <limit>
                                <counter>LINE</counter>
                                <value>COVEREDRATIO</value>
                                <minimum>0.80</minimum>
                            </limit>
                        </limits>
                    </rule>
                </rules>
                <excludes>
                    <!-- Exclude generated gRPC/Protobuf classes -->
                    <exclude>com/showcase/grpc/**</exclude>
                    <!-- Exclude main application entry points -->
                    <exclude>**/*Application.class</exclude>
                </excludes>
            </configuration>
        </execution>
    </executions>
</plugin>
```

---

## 7. Error Handling Conventions

### Spring Boot — Global Exception Handler

Each Spring Boot service must have a `@RestControllerAdvice` class that maps domain exceptions to HTTP responses:

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AccountVerificationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorResponse handleAccountVerification(AccountVerificationException ex) {
        Span.current().setStatus(StatusCode.ERROR, ex.getMessage());
        Span.current().recordException(ex);
        return new ErrorResponse("VERIFICATION_FAILED", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleGeneral(Exception ex) {
        Span.current().setStatus(StatusCode.ERROR, "Unexpected error");
        Span.current().recordException(ex);
        return new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred");
    }
}
```

### Quarkus — `@ServerExceptionMapper`

```java
@ServerExceptionMapper
public RestResponse<ErrorResponse> handleAccountException(AccountVerificationException ex) {
    Span.current().setStatus(StatusCode.ERROR, ex.getMessage());
    Span.current().recordException(ex);
    return RestResponse.status(Response.Status.UNPROCESSABLE_ENTITY,
        new ErrorResponse("VERIFICATION_FAILED", ex.getMessage()));
}
```

---

## 8. Logging Standards

- Use **SLF4J** in Spring Boot services (backed by Logback).
- Use **JBoss Logging** (Quarkus default) in Quarkus services — do not add SLF4J as a Quarkus dependency.
- Log at `INFO` for business events (payment received, account approved, etc.).
- Log at `DEBUG` for internal state (never in production default config).
- Log at `ERROR` only when catching exceptions — always include the exception.
- **Always include `transactionId`** in log messages where it is available. Use MDC (Spring) or Quarkus MDC to propagate it.

```java
// Spring Boot
MDC.put("transactionId", transactionId);
log.info("Payment accepted for processing");

// Quarkus
MDC.put("transactionId", transactionId);
Log.infof("Account verification approved for transaction %s", transactionId);
```

---

## 9. Frontend Conventions (`spa-mobile-app`)

- Use **Vue 3 Composition API** (`<script setup>`) exclusively. No Options API.
- **No global state management** (Pinia/Vuex) — use composables (`use*.js`) for shared logic.
- One composable per concern: `useKeycloak.js`, `usePayment.js`, `usePaymentStream.js`.
- Components are in `src/components/`, views (route-level) in `src/views/`.
- All API calls go through a central `src/api/payments.js` module — never call `fetch` directly from a component.
- `src/config.js` reads from `window.__APP_CONFIG__` (injected by Nginx at container startup) with fallback to Vite env vars for local dev.
- Handle SSE disconnections gracefully — show a "Connection lost, retrying..." state.
- Vitest tests live alongside components in `*.spec.js` files.
