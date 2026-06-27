# R08 — 🔴 account-verifier: negative amount credits account balance (security bug)

## Problem
`AccountVerificationService.java` — no guard against `amount ≤ 0`:
- `balance.compareTo(negativeAmount) < 0` is always **false** for any negative amount
  (any balance is greater than a negative number) → the payment is approved.
- `UPDATE balance = balance - (-100)` **adds** money to the source account.

A call with `amount = -100.0` via the gRPC API silently credits the account.

## File to change
- `apps/account-verifier/src/main/java/com/showcase/verifier/service/AccountVerificationService.java`
- `apps/account-verifier/src/test/java/com/showcase/verifier/service/AccountVerificationServiceTest.java`

## Fix
Add an explicit validation block before the balance check:

```java
if (amount.compareTo(BigDecimal.ZERO) <= 0) {
    return VerificationResult.ofRejected("Amount must be positive");
}
```

Also validate `sourceAccount` and `destinationAccount` are non-blank, and that they differ
(no self-transfers).

## Acceptance
- `verifyAccount` with `amount = -1` returns `approved = false`
- `verifyAccount` with `amount = 0` returns `approved = false`
- Tests added for both cases
