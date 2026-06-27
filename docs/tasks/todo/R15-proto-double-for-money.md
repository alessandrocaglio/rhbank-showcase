# R15 — 🔴 All services: `double` used for monetary amount in proto (precision loss)

## Problem
`apps/grpc-api/src/main/proto/account.proto` declares `amount` as `double`. IEEE 754 `double`
cannot represent many decimal values exactly (e.g. 0.1 + 0.2 ≠ 0.3). Precision is lost when the
payment-gateway serialises the amount into the proto on the wire. The workaround in
`AccountServiceGrpcImpl.java:43` (`new BigDecimal(String.valueOf(double))`) only partially
compensates — the loss already occurred during serialisation.

`PaymentServiceImpl.java:37` in `payment-gateway` also does `request.amount().doubleValue()`
before passing to gRPC, compounding the issue.

## Files to change
- `apps/grpc-api/src/main/proto/account.proto`
- `apps/payment-gateway/src/main/java/com/showcase/gateway/service/PaymentServiceImpl.java`
- `apps/account-verifier/src/main/java/com/showcase/verifier/grpc/AccountServiceGrpcImpl.java`
- Tests that construct `VerifyAccountRequest` with double amounts

## Fix
Change proto field `amount` from `double` to `string`:
```protobuf
message VerifyAccountRequest {
  string transaction_id      = 1;
  string source_account      = 2;
  string destination_account = 3;
  string amount              = 4;   // decimal string, e.g. "100.00"
  string currency            = 5;
}
```

Sender (`PaymentServiceImpl`): `request.amount().toPlainString()`.
Receiver (`AccountServiceGrpcImpl`): `new BigDecimal(request.getAmount())`.

## Acceptance
- `amount = 0.10` arrives at account-verifier as exactly `BigDecimal("0.10")`
- Proto rebuild (`./mvnw -pl apps/grpc-api generate-sources`) succeeds
- All tests pass with updated request construction
