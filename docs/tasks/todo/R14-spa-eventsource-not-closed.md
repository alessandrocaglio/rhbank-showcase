# R14 — 🔴 SPA: EventSource left open after COMPLETED/FAILED status received

## Problem
`src/composables/usePaymentStream.js` — when the server sends a terminal status (`COMPLETED` or
`FAILED`), the `onmessage` handler updates the reactive refs but leaves the `EventSource` open.
The server closes its end, triggering the browser's built-in SSE reconnect mechanism (distinct from
the app's own `onerror` retry). This causes unexpected reconnection attempts after a payment
completes, may emit duplicate events, and wastes a server-side connection slot.

## File to change
- `apps/spa-mobile-app/src/composables/usePaymentStream.js`

## Fix
Call `cleanup()` inside `onmessage` when a terminal status is received:

```javascript
eventSource.onmessage = (event) => {
  const data = JSON.parse(event.data)
  status.value = data.status
  detail.value = data.detail
  timestamp.value = data.timestamp
  isConnected.value = true

  if (data.status === 'COMPLETED' || data.status === 'FAILED') {
    cleanup()   // close EventSource — no reconnection needed
  }
}
```

## Acceptance
- After `COMPLETED` is received, `DevTools → Network` shows the SSE connection closed (no
  additional reconnect requests)
- Test in `usePaymentStream.spec.js` asserts `cleanup()` is called on terminal status
