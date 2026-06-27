# R27 — 🟠 account-verifier: raw exception messages leaked to gRPC callers

## Problem
`AccountServiceGrpcImpl.java:55`:
```java
responseObserver.onError(Status.INTERNAL
    .withDescription(ar.cause().getMessage())
    .withCause(ar.cause())
    .asRuntimeException());
```
`ar.cause().getMessage()` can expose raw exception messages to the calling service — including SQL
error text, table names, Hibernate internal state, or stack detail. The `payment-gateway` logs this
message and surfaces part of it in the HTTP 422 response body returned to the browser.

## File to change
- `apps/account-verifier/src/main/java/com/showcase/verifier/grpc/AccountServiceGrpcImpl.java`

## Fix
Return a generic message to the caller; log the detail server-side:

```java
LOG.errorf(ar.cause(), "Verification failed for transactionId=%s", request.getTransactionId());
Span.current().recordException(ar.cause());
responseObserver.onError(Status.INTERNAL
    .withDescription("Account verification failed. See server logs.")
    .asRuntimeException());
```

## Acceptance
- Simulated DB failure: payment-gateway receives a generic error description, not SQL text
- The full exception is visible in server logs and traces (via `Span.recordException`)
