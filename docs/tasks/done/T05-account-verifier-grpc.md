# T05 · account-verifier: gRPC Service Implementation

**Phase:** 2 — account-verifier (Quarkus 3.15.x)
**Status:** todo
**Depends on:** T04, T02

## Deliverables
- `AccountVerificationService` (`@ApplicationScoped`) — business logic
  - Check balance ≥ requested amount
  - Check account `status = 'ACTIVE'`
  - Deduct balance + persist within the same JTA transaction
  - Returns `VerificationResult` record (`approved`, `reason`)
- `AccountServiceGrpcImpl` (`@GrpcService`) — implements generated `AccountService` interface, delegates to `AccountVerificationService`, sets OTel span attributes:
  - `bank.payment.transaction_id`, `bank.account.source`, `bank.account.approved`
- `GlobalExceptionMapper` — maps domain exceptions to gRPC `Status` codes

## Unit Tests
`AccountVerificationServiceTest` (`@QuarkusTest`, `@InjectMock AccountRepository`):
- Approved path: balance sufficient, status ACTIVE → `approved=true`
- Rejected: insufficient balance → `approved=false`, reason non-blank
- Rejected: account status SUSPENDED → `approved=false`
- Rejected: account not found → `approved=false`

`AccountServiceGrpcImplTest` (`@QuarkusTest` with embedded gRPC client):
- RPC returns `approved=true` on successful verification
- RPC returns `approved=false` on failed verification

## Verification
```bash
./mvnw test -pl apps/account-verifier
# All 6 test cases pass
# JaCoCo line coverage ≥ 80%
```

## Acceptance Criteria
- [ ] All `AccountVerificationServiceTest` cases pass
- [ ] All `AccountServiceGrpcImplTest` cases pass
- [ ] Span attributes set without SDK import (OTel API only)
- [ ] JaCoCo line coverage ≥ 80%
