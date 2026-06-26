# T09 · payment-gateway: gRPC Client + PaymentService Implementation

**Phase:** 3 — payment-gateway (Spring Boot 3.3.x)
**Status:** todo
**Depends on:** T08, T02

## Deliverables
- `AccountVerifierClient` (`@Component`) — wraps `AccountServiceGrpc.AccountServiceBlockingStub` via `net.devh` `@GrpcClient("account-verifier")`; maps gRPC `StatusRuntimeException` → `AccountVerificationException`
- `AccountVerificationException` (domain exception) — carries reason string
- `PaymentServiceImpl` (implements `PaymentService`) — replaces stub from T08:
  - Generates `txId` (UUID)
  - Calls `AccountVerifierClient.verify()`
  - Sets OTel span attributes: `bank.payment.transaction_id`, `bank.payment.source_account`, `bank.payment.amount`, `bank.payment.currency`
  - On gRPC rejection → throws `AccountVerificationException`
- Wire `SseEmitterService.register(txId)` call before gRPC call so SSE is ready

## Unit Tests
`AccountVerifierClientTest` (`@ExtendWith(MockitoExtension.class)`):
- Successful gRPC → returns approved result
- gRPC `INVALID_ARGUMENT` status → throws `AccountVerificationException`

`PaymentServiceImplTest` (`@ExtendWith(MockitoExtension.class)`):
- Approved path: UUID generated, gRPC called, result returned
- Rejected path: `AccountVerificationException` propagated
- Span attributes set on `Span.current()` (verify via `Span.current()` mock or OTel testing SDK)

## Verification
```bash
./mvnw test -pl apps/payment-gateway
# AccountVerifierClientTest and PaymentServiceImplTest pass
# JaCoCo line coverage ≥ 80%
```

## Acceptance Criteria
- [ ] `AccountVerifierClientTest` — both cases pass
- [ ] `PaymentServiceImplTest` — all 3 cases pass
- [ ] Span attributes set using OTel API only (no SDK dependency)
- [ ] JaCoCo line coverage ≥ 80%
