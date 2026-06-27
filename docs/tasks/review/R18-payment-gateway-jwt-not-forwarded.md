# R18 — 🟠 payment-gateway: JWT subject not forwarded to gRPC downstream

## Problem
`PaymentServiceImpl.java` does not extract the authenticated user's JWT from `SecurityContextHolder`
and attach it to the outgoing gRPC call metadata. `account-verifier` has no way to know who
initiated the payment.

CLAUDE.md lists as a **non-negotiable core objective**:
> "JWT identity (from Keycloak) rides alongside active spans across every service hop"

This requirement is currently unimplemented.

## Files to change
- `apps/payment-gateway/src/main/java/com/showcase/gateway/service/PaymentServiceImpl.java`
- `apps/payment-gateway/src/main/java/com/showcase/gateway/client/AccountVerifierClient.java`
- `apps/account-verifier/src/main/java/com/showcase/verifier/grpc/AccountServiceGrpcImpl.java`
  (to log / record the forwarded identity)

## Fix
In `PaymentServiceImpl`, extract the JWT token string from the security context and pass it:

```java
String bearerToken = ((JwtAuthenticationToken) SecurityContextHolder.getContext()
    .getAuthentication()).getToken().getTokenValue();
```

In `AccountVerifierClient`, attach it as gRPC metadata before the call:

```java
Metadata metadata = new Metadata();
metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
    "Bearer " + bearerToken);
stub = MetadataUtils.attachHeaders(stub, metadata);
```

In `AccountServiceGrpcImpl`, read the header and record it as a span attribute:
```java
String bearer = headers.get(Metadata.Key.of("authorization", ASCII_STRING_MARSHALLER));
```

## Acceptance
- Grafana Tempo trace for a payment shows the JWT subject/username as a span attribute on the
  `account-verifier` gRPC server span
