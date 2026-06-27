# R17 — 🟠 payment-gateway: no gRPC deadline — thread pool exhaustion under load

## Problem
`AccountVerifierClient.java` calls `stub.verifyAccount(request)` with no deadline. If
`account-verifier` is slow or hung, the HTTP thread blocks indefinitely. Under concurrent load
this exhausts the Tomcat thread pool and the gateway stops serving all requests — a single
downstream dependency failure takes down the entire edge API.

## File to change
- `apps/payment-gateway/src/main/java/com/showcase/gateway/client/AccountVerifierClient.java`

## Fix
Add a deadline to the blocking stub call:

```java
VerifyAccountResponse response = stub
    .withDeadlineAfter(5, TimeUnit.SECONDS)
    .verifyAccount(request);
```

Make the timeout configurable via `application.yml`:
```yaml
app:
  grpc:
    account-verifier-timeout-seconds: 5
```

## Acceptance
- Simulated slow account-verifier (add a sleep > 5s) → payment request returns 504/503 within
  ~5 seconds rather than blocking indefinitely
- `AccountVerifierClientTest` has a timeout scenario
