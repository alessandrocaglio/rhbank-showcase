# R13 — 🔴 SPA: expired token sent on payment API call without refresh

## Problem
`src/api/payments.js:6` calls `getToken()` which returns `keycloak.token` directly with no
expiry check. If the token is within Keycloak's `onTokenExpired` 30-second window but `updateToken()`
has not yet resolved, `POST /api/v1/payments` goes out with an expired token and receives a 401.
There is no retry logic in `initiatePayment` — the error is displayed as a generic failure.

## File to change
- `apps/spa-mobile-app/src/api/payments.js`

## Fix
Proactively refresh the token before making any authenticated request:

```javascript
export async function initiatePayment(payload) {
  const { getToken } = useKeycloak()
  // refresh if token expires within next 30 seconds
  await keycloak.updateToken(30).catch(() => keycloak.login())
  const res = await fetch(`${config.apiBaseUrl}/api/v1/payments`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${getToken()}`,
    },
    body: JSON.stringify(payload),
  })
  ...
}
```

The `keycloak` instance needs to be exported from `useKeycloak.js` or `updateToken` wrapped
in the composable's public API.

## Acceptance
- A payment submitted when the token is 4m50s old (just before expiry) succeeds, not 401s
