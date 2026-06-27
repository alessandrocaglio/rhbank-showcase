# R09 — 🔴 account-verifier: TOCTOU balance race under concurrent payments

## Problem
`AccountVerificationService.java:47-60` — balance check and deduction are two separate operations
with no locking between them:
1. `findByIdOptional(sourceAccount)` — reads current balance
2. Check: `balance.compareTo(amount) < 0`
3. `update("balance = balance - ?1 where accountId = ?2")`

Two concurrent requests for the same account, both with amounts that individually pass the check,
can both read the same balance, both pass step 2, and both execute step 3 — resulting in a negative
balance. The schema has no `CHECK (balance >= 0)` constraint as a last line of defence.

## Files to change
- `apps/account-verifier/src/main/java/com/showcase/verifier/service/AccountVerificationService.java`
- `apps/account-verifier/src/main/resources/db/migration/V1__init_accounts.sql`

## Fix
**Option A — Pessimistic lock:**
Replace `findByIdOptional` with a `SELECT FOR UPDATE` query:
```java
Account account = accountRepository.find("accountId", accountId)
    .withLock(LockModeType.PESSIMISTIC_WRITE)
    .firstResultOptional()
    .orElseThrow(...);
```

**Option B — Conditional UPDATE:**
Change to a single atomic `UPDATE ... WHERE balance >= :amount` and check affected rows:
```sql
UPDATE account SET balance = balance - :amount
WHERE account_id = :id AND balance >= :amount
```
If 0 rows updated → insufficient funds.

**Schema fix (always):**
```sql
ALTER TABLE accounts ADD CONSTRAINT chk_balance_nonneg CHECK (balance >= 0);
```

## Acceptance
- Concurrent gRPC calls for the same account never result in a negative balance
- DB constraint rejects any balance update that would go negative
