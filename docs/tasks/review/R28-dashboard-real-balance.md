# R28 — Feature: Dashboard real account balance

## Problem / Current state

`DashboardView.vue` renders the balance card with a hardcoded string `$5,000.00` and a
hardcoded account identifier `ACC-001 · USD`. Neither value is fetched from any backend.
The logged-in user has no relationship to any account in the code — `PaymentsView.vue`
presents all four accounts in a static `<select>` dropdown, and the user simply picks one.
There is no API endpoint in payment-gateway that returns a balance, and `account.proto`
has no RPC that can query a balance without initiating a payment.

---

## Architecture analysis

Real balance data lives in `accounts_db` (PostgreSQL), managed by account-verifier (Quarkus).
The table is `accounts(account_id, customer_name, balance, status)`.

The SPA may only call payment-gateway over HTTP — it cannot reach account-verifier directly
(no HTTP port is exposed by account-verifier for external traffic; only gRPC on 9090 and a
Quarkus management HTTP port on 8080 for health probes).

The cleanest path that keeps all cross-service traffic observable and trace-propagated is:

1. Add a `GetBalance` RPC to `account.proto` (account-verifier gRPC server).
2. Implement it in `AccountServiceGrpcImpl` (account-verifier).
3. Add a new HTTP endpoint `GET /api/v1/accounts/{accountId}/balance` to payment-gateway
   that calls account-verifier via gRPC (`GetBalance` RPC) and returns the result as JSON.
4. Protect that endpoint with JWT (same `payment-init` role the POST uses, or just
   `authenticated()`).
5. Add a `useAccount.js` composable in the SPA that fetches balance and exposes reactive refs.
6. Update `DashboardView.vue` to display live balance.

A simpler alternative — adding a direct JDBC datasource to payment-gateway pointing at
`accounts_db` — is deliberately rejected: it couples two services at the DB layer, violates
the service boundary, and removes trace propagation across the gRPC call (which is a core
showcase objective). The gRPC path keeps the observability story intact.

---

## User to account mapping

**Decision: convention-based mapping via Keycloak `preferred_username` claim.**

The demo has four accounts seeded in Flyway migration `V1__init_accounts.sql`:

| account_id | customer_name  | keycloak username |
|------------|---------------|-------------------|
| ACC-001    | Alice Martin  | `alice`           |
| ACC-002    | Bob Johnson   | `bob`             |
| ACC-003    | Charlie Brown | `charlie`         |
| ACC-004    | Diana Prince  | `diana`           |

The simplest demo-appropriate approach is to add an `owner_username` column to the
`accounts` table via a new Flyway migration in account-verifier, populated from the seed
data above. The new `GetBalance` gRPC RPC accepts the Keycloak `preferred_username` from
the JWT as its lookup key (not the raw account_id — that would let any user query any
account). payment-gateway extracts `preferred_username` from the JWT principal and passes
it to the RPC. This makes the demo realistic without requiring a separate user-management
service.

The `PaymentsView.vue` source-account selector remains a static list for now — that is
addressed separately. Dashboard balance always shows the account owned by the logged-in
user.

---

## Implementation plan

### Layer 0: accounts_db — add owner column

**File:** `apps/account-verifier/src/main/resources/db/migration/V5__add_owner_username.sql`

```sql
ALTER TABLE accounts ADD COLUMN owner_username VARCHAR(100);

UPDATE accounts SET owner_username = 'alice'   WHERE account_id = 'ACC-001';
UPDATE accounts SET owner_username = 'bob'     WHERE account_id = 'ACC-002';
UPDATE accounts SET owner_username = 'charlie' WHERE account_id = 'ACC-003';
UPDATE accounts SET owner_username = 'diana'   WHERE account_id = 'ACC-004';
```

No NOT NULL constraint yet — existing rows are already back-filled in the same script.
Future accounts must supply the column.

### Layer 1: grpc-api — extend account.proto

**File:** `apps/grpc-api/src/main/proto/account.proto`

Add after the existing `VerifyAccount` RPC inside the `AccountService` service block:

```protobuf
// Returns the current balance and account metadata for the account owned by the
// authenticated user (identified by their Keycloak preferred_username).
rpc GetBalance (GetBalanceRequest) returns (GetBalanceResponse);
```

Add new message types at the end of the file:

```protobuf
message GetBalanceRequest {
  // Keycloak preferred_username of the requesting user.
  string username = 1;
}

message GetBalanceResponse {
  bool   found        = 1;
  string account_id   = 2;
  string owner        = 3;
  double balance      = 4;
  string currency     = 5;   // always "USD" for this demo
  string status       = 6;
}
```

Rebuilding the `grpc-api` Maven module (`./mvnw clean package -pl apps/grpc-api`) regenerates
all Java stubs automatically via the `protobuf-maven-plugin`.

### Layer 2: account-verifier — implement GetBalance RPC

**File (new):** `apps/account-verifier/src/main/java/com/showcase/verifier/repository/AccountRepository.java`

Add one new finder method to the existing `AccountRepository` interface (which extends
`PanacheRepositoryBase<Account, String>`):

```java
public Optional<Account> findByOwnerUsername(String username) {
    return find("ownerUsername", username).firstResultOptional();
}
```

**File:** `apps/account-verifier/src/main/java/com/showcase/verifier/domain/Account.java`

Add the new `ownerUsername` field:

```java
@Column(name = "owner_username")
private String ownerUsername;
```

Add a getter `getOwnerUsername()`.

**File:** `apps/account-verifier/src/main/java/com/showcase/verifier/grpc/AccountServiceGrpcImpl.java`

Add a new `@Override` of `getBalance(GetBalanceRequest, StreamObserver<GetBalanceResponse>)`:

```java
@Override
public void getBalance(GetBalanceRequest request,
                       StreamObserver<GetBalanceResponse> responseObserver) {
    vertx.executeBlocking(() -> accountRepository.findByOwnerUsername(request.getUsername()))
        .onComplete(ar -> {
            if (ar.failed()) {
                responseObserver.onError(Status.INTERNAL
                        .withDescription(ar.cause().getMessage())
                        .withCause(ar.cause())
                        .asRuntimeException());
                return;
            }
            var opt = ar.result();
            if (opt.isEmpty()) {
                responseObserver.onNext(GetBalanceResponse.newBuilder()
                        .setFound(false)
                        .build());
            } else {
                var account = opt.get();
                responseObserver.onNext(GetBalanceResponse.newBuilder()
                        .setFound(true)
                        .setAccountId(account.getAccountId())
                        .setOwner(account.getCustomerName())
                        .setBalance(account.getBalance().doubleValue())
                        .setCurrency("USD")
                        .setStatus(account.getStatus())
                        .build());
            }
            responseObserver.onCompleted();
        });
}
```

Note: `AccountRepository` must be injected alongside `AccountVerificationService` in this
class. Because `findByOwnerUsername` is a read-only query, no `@Transactional` is needed
on the Vert.x worker thread for this path (Panache read queries start their own transaction
implicitly in Quarkus).

### Layer 3: payment-gateway — new BalanceController and client method

**File (new):** `apps/payment-gateway/src/main/java/com/showcase/gateway/dto/BalanceResponse.java`

```java
package com.showcase.gateway.dto;

public record BalanceResponse(
        String accountId,
        String owner,
        double balance,
        String currency,
        String status
) {}
```

**File:** `apps/payment-gateway/src/main/java/com/showcase/gateway/client/AccountVerifierClient.java`

Add a second method alongside `verify()`:

```java
public BalanceResponse getBalance(String username) {
    GetBalanceRequest request = GetBalanceRequest.newBuilder()
            .setUsername(username)
            .build();
    try {
        GetBalanceResponse response = stub.getBalance(request);
        if (!response.getFound()) {
            return null;  // caller handles 404
        }
        return new com.showcase.gateway.dto.BalanceResponse(
                response.getAccountId(),
                response.getOwner(),
                response.getBalance(),
                response.getCurrency(),
                response.getStatus());
    } catch (io.grpc.StatusRuntimeException ex) {
        throw new AccountVerificationException(
                "gRPC GetBalance call failed: " + ex.getStatus().getDescription());
    }
}
```

Note: `AccountVerifierClient` already holds the injected `AccountServiceGrpc.AccountServiceBlockingStub`
(`stub`). The blocking stub is regenerated from the updated proto and will now have a
`getBalance()` method automatically.

**File (new):** `apps/payment-gateway/src/main/java/com/showcase/gateway/controller/AccountController.java`

```java
package com.showcase.gateway.controller;

import com.showcase.gateway.client.AccountVerifierClient;
import com.showcase.gateway.dto.BalanceResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountVerifierClient accountVerifierClient;

    public AccountController(AccountVerifierClient accountVerifierClient) {
        this.accountVerifierClient = accountVerifierClient;
    }

    @GetMapping("/me/balance")
    public ResponseEntity<BalanceResponse> getMyBalance(@AuthenticationPrincipal Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        BalanceResponse balance = accountVerifierClient.getBalance(username);
        if (balance == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(balance);
    }
}
```

Endpoint: `GET /api/v1/accounts/me/balance`
Auth: requires a valid JWT (any authenticated user); the JWT's `preferred_username` claim
determines which account is queried. The user cannot request another user's balance.

**File:** `apps/payment-gateway/src/main/java/com/showcase/gateway/config/SecurityConfig.java`

In the `authorizeHttpRequests` chain, add before `.anyRequest().authenticated()`:

```java
.requestMatchers(HttpMethod.GET, "/api/v1/accounts/me/balance").authenticated()
```

CORS: The existing CORS config allows `GET` for all paths under `/**`, so no CORS change
is needed for the new endpoint.

### Layer 4: SPA — new composable and view update

**File (new):** `apps/spa-mobile-app/src/api/accounts.js`

```js
import { useKeycloak } from '../composables/useKeycloak.js'
import { config } from '../config.js'

export async function fetchMyBalance() {
  const { getToken } = useKeycloak()
  const res = await fetch(`${config.apiBaseUrl}/api/v1/accounts/me/balance`, {
    headers: { 'Authorization': `Bearer ${getToken()}` },
  })
  if (res.status === 404) return null
  if (!res.ok) throw new Error(`Balance fetch failed: HTTP ${res.status}`)
  return res.json()  // { accountId, owner, balance, currency, status }
}
```

**File (new):** `apps/spa-mobile-app/src/composables/useAccount.js`

```js
import { ref, onMounted } from 'vue'
import { fetchMyBalance } from '../api/accounts.js'

export function useAccount() {
  const balance = ref(null)        // null = loading
  const accountId = ref('')
  const currency = ref('USD')
  const balanceError = ref('')
  const balanceLoading = ref(true)

  async function loadBalance() {
    balanceLoading.value = true
    balanceError.value = ''
    try {
      const data = await fetchMyBalance()
      if (data) {
        balance.value = data.balance
        accountId.value = data.accountId
        currency.value = data.currency
      } else {
        balanceError.value = 'No account found'
      }
    } catch (e) {
      balanceError.value = 'Could not load balance'
    } finally {
      balanceLoading.value = false
    }
  }

  onMounted(loadBalance)

  return { balance, accountId, currency, balanceError, balanceLoading, loadBalance }
}
```

The `loadBalance` function is returned so `DashboardView.vue` can call it after a payment
completes (see balance refresh below).

**File:** `apps/spa-mobile-app/src/views/DashboardView.vue`

Replace the entire `<script setup>` block and the balance-card section of the template.

Script setup:

```js
import { useRouter } from 'vue-router'
import { useKeycloak } from '../composables/useKeycloak.js'
import { useAccount } from '../composables/useAccount.js'

const router = useRouter()
const { username } = useKeycloak()
const { balance, accountId, currency, balanceError, balanceLoading } = useAccount()

const formattedBalance = computed(() => {
  if (balance.value === null) return '—'
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: currency.value,
  }).format(balance.value)
})
```

Add `computed` to the import: `import { computed } from 'vue'`

Balance card template replacement:

```html
<div class="balance-card">
  <p class="balance-label">Available Balance</p>
  <h2 class="balance-amount">
    <span v-if="balanceLoading">Loading…</span>
    <span v-else-if="balanceError" class="balance-error">{{ balanceError }}</span>
    <span v-else>{{ formattedBalance }}</span>
  </h2>
  <p class="account-number">{{ accountId || '…' }} &middot; {{ currency }}</p>
</div>
```

Add a minimal `.balance-error` style within `<style scoped>`:

```css
.balance-error {
  font-size: 1rem;
  color: rgba(255, 255, 255, 0.75);
}
```

**Balance refresh after payment completes**

The existing SSE flow in the payment status view (`/payments/:txId/status`) resolves when
a `payment-completed` event arrives. `DashboardView.vue` is not visible during that flow,
but the user navigates back to `/dashboard` after completion (or the status view pushes
them there). To refresh the balance on that return navigation, use Vue Router's `onActivated`
lifecycle hook (since DashboardView is likely inside `<keep-alive>`) or simply call
`loadBalance()` in `onActivated`:

In `DashboardView.vue` script setup, add:

```js
import { computed, onActivated } from 'vue'
// ...
const { balance, accountId, currency, balanceError, balanceLoading, loadBalance } = useAccount()
onActivated(loadBalance)
```

If `<keep-alive>` is not used, `onMounted` inside `useAccount` already re-fetches on
each route navigation to `/dashboard`. Either way, balance will reflect the post-payment
state.

---

## New files

- `apps/account-verifier/src/main/resources/db/migration/V5__add_owner_username.sql` — adds `owner_username` column and populates seed data
- `apps/payment-gateway/src/main/java/com/showcase/gateway/controller/AccountController.java` — REST endpoint `GET /api/v1/accounts/me/balance`
- `apps/payment-gateway/src/main/java/com/showcase/gateway/dto/BalanceResponse.java` — JSON response record
- `apps/spa-mobile-app/src/api/accounts.js` — fetch helper for the balance endpoint
- `apps/spa-mobile-app/src/composables/useAccount.js` — reactive balance state composable

## Modified files

- `apps/grpc-api/src/main/proto/account.proto` — add `GetBalance` RPC, `GetBalanceRequest` and `GetBalanceResponse` messages
- `apps/account-verifier/src/main/java/com/showcase/verifier/domain/Account.java` — add `ownerUsername` field and `getOwnerUsername()` getter
- `apps/account-verifier/src/main/java/com/showcase/verifier/repository/AccountRepository.java` — add `findByOwnerUsername(String username)` method
- `apps/account-verifier/src/main/java/com/showcase/verifier/grpc/AccountServiceGrpcImpl.java` — inject `AccountRepository`, add `getBalance` override
- `apps/payment-gateway/src/main/java/com/showcase/gateway/client/AccountVerifierClient.java` — add `getBalance(String username)` method
- `apps/payment-gateway/src/main/java/com/showcase/gateway/config/SecurityConfig.java` — permit `GET /api/v1/accounts/me/balance` for authenticated users
- `apps/spa-mobile-app/src/views/DashboardView.vue` — replace hardcoded balance card with live data from `useAccount`

---

## Acceptance criteria

- `GET /api/v1/accounts/me/balance` with a valid JWT for `alice` returns HTTP 200 with
  `{ "accountId": "ACC-001", "owner": "Alice Martin", "balance": <number>, "currency": "USD", "status": "ACTIVE" }`.
- `GET /api/v1/accounts/me/balance` without a JWT returns HTTP 401.
- `GET /api/v1/accounts/me/balance` with a JWT whose `preferred_username` has no matching
  account returns HTTP 404.
- Dashboard balance card shows the real balance value formatted as a USD currency string
  (e.g., `$10,000.00` for `alice`).
- Dashboard balance card shows `Loading…` while the fetch is in flight.
- Dashboard balance card shows an inline error message (no crash) when the API is
  unreachable.
- After submitting a payment from `ACC-001` for $100, navigating back to `/dashboard`
  shows the balance decreased by $100 (e.g., `$9,900.00`).
- The `GetBalance` gRPC call appears as a child span of the HTTP request span in Grafana
  Tempo, with `traceparent` propagated through gRPC metadata automatically by the OTel
  Java Agent.
- `./mvnw test -pl apps/account-verifier` passes with a new unit test
  `AccountServiceGrpcImplTest` case covering `getBalance` for a found and a not-found
  username.
- `./mvnw test -pl apps/payment-gateway` passes with a new `AccountControllerTest` that
  mocks `AccountVerifierClient.getBalance()` and asserts the 200 and 404 responses.
