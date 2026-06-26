# T08 · payment-gateway: REST Controller + Request/Response DTOs

**Phase:** 3 — payment-gateway (Spring Boot 3.3.x)
**Status:** todo
**Depends on:** T07

## Deliverables
- `PaymentRequest` record — `@NotBlank sourceAccount`, `@NotBlank destinationAccount`, `@Positive amount`, `@NotBlank currency` (Bean Validation)
- `PaymentResponse` record — `transactionId`, `status`, `message`
- `ErrorResponse` record — `code`, `message`
- `PaymentService` interface — `PaymentResponse initiate(PaymentRequest, String userId)`
- `PaymentServiceStub` (`@Service`) — placeholder: generates UUID, returns PENDING (to be replaced in T09)
- `PaymentController` (`@RestController`, `/api/v1/payments`):
  - `POST /` → 202, delegates to `PaymentService`
  - `GET /stream/{transactionId}` → `text/event-stream`, delegates to `SseEmitterService` (stub interface)
- `SseEmitterService` interface — `SseEmitter register(String txId)`, `void resolve(String txId, PaymentStatusEvent event)`
- `GlobalExceptionHandler` (`@RestControllerAdvice`) — `AccountVerificationException` → 422, `Exception` → 500

## Unit Tests
`PaymentControllerTest` (`@WebMvcTest(PaymentController.class)`):
- Valid request with `payment-init` role → 202, body contains `transactionId`
- Missing `sourceAccount` → 400
- Negative `amount` → 400
- No JWT → 401
- Missing role → 403
- `PaymentService` throws `AccountVerificationException` → 422

## Verification
```bash
./mvnw test -pl apps/payment-gateway
# PaymentControllerTest — all 6 cases pass
# JaCoCo line coverage ≥ 80%
```

## Acceptance Criteria
- [ ] All 6 controller test cases pass
- [ ] Bean Validation annotations trigger 400 on invalid input
- [ ] `GlobalExceptionHandler` maps exceptions to correct HTTP status
- [ ] JaCoCo line coverage ≥ 80%
