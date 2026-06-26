# T19 · spa-mobile-app: SSE Status Page

**Phase:** 6 — spa-mobile-app (Vue 3)
**Status:** todo
**Depends on:** T18

## Design Reference

See https://github.com/alessandrocaglio/rhbank — `DashboardPage.tsx` for card and list-item patterns.

### PaymentStatusView Design
- White card, `2rem` padding, `8px` radius, shadow
- Transaction ID displayed in monospace, muted secondary color
- Status badge (pill): green bg `#22c55e` / white text for COMPLETED; red `#ee0000` for FAILED; amber `#f59e0b` for PENDING
- Detail message: normal body text below badge
- Connection indicator: small dot (green = connected, grey = disconnected) + "Live" / "Reconnecting…" label
- "Back to Payments" link at bottom

## Deliverables

- `src/composables/usePaymentStream.js` — wraps `EventSource`:
  - Opens `{apiBaseUrl}/api/v1/payments/stream/{txId}`
  - Reactive: `status` (string), `detail` (string), `isConnected` (bool)
  - On `message` event → parses JSON, updates `status` and `detail`
  - On `error` event → `isConnected = false`; retry up to 3× with 2s backoff via `setTimeout`; after 3 failures sets `status = 'CONNECTION_LOST'`
  - `cleanup()` → `eventSource.close()`; called in `onUnmounted`
- `src/views/PaymentStatusView.vue` — status page matching reference design

## Unit Tests

`src/composables/__tests__/usePaymentStream.spec.js` (mock `EventSource` via `vi.stubGlobal`):
- `EventSource` constructed with correct URL including `txId`
- `message` event with `{ status: 'COMPLETED', detail: 'OK' }` → `status.value === 'COMPLETED'`
- `error` event → `isConnected.value === false`; reconnect attempted (`vi.useFakeTimers`)
- `cleanup()` calls `eventSource.close()`
- Mount `PaymentStatusView` → unmount → `close()` called

## Verification
```bash
cd apps/spa-mobile-app
npm test
# Full suite: useKeycloak, useTheme, usePayment, PaymentsView, usePaymentStream — all green
```

## Acceptance Criteria
- [ ] All 5 `usePaymentStream` test cases pass
- [ ] Full Vitest suite exits 0 (regression: all previous specs still green)
- [ ] Status badge colours match reference for COMPLETED / FAILED / PENDING
- [ ] Connection indicator shows live/reconnecting states
- [ ] `cleanup()` called on unmount (verified by test)
