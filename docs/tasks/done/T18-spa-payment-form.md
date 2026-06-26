# T18 · spa-mobile-app: Payment Form + API Layer

**Phase:** 6 — spa-mobile-app (Vue 3)
**Status:** todo
**Depends on:** T17

## Design Reference

See https://github.com/alessandrocaglio/rhbank — specifically `PaymentPage.tsx` and `App.css`.

### PaymentPage Design
- White card, `2rem` padding, `8px` border-radius, shadow `0 4px 12px rgba(0,0,0,0.05)`
- Max-width: `400px`, centered
- H2 "Send Money" — `#ee0000`, centered, `1.5rem` margin-bottom
- Input fields: `100%` width, `0.75rem` padding, `1px solid #e0e0e0` border, `4px` radius
- Input focus: `border-color: #ee0000`, `box-shadow: 0 0 0 2px rgba(238,0,0,0.25)`
- `1.5rem` margin-bottom per field group
- Payment type radio group: "Instant" / "Standard" options, flex with `1rem` gap
- Submit button: `100%` width, `#ee0000` background, white text, hover `#cc0000`
- Inline error messages: `font-size: 0.75rem`, `color: #ee0000`, displayed below invalid field

## Deliverables

- `src/api/payments.js` — central API module:
  - `initiatePayment(payload)` → `POST {apiBaseUrl}/api/v1/payments` with `Authorization: Bearer <token>` header; returns `{ transactionId, status, message }`
  - `getPaymentStream(txId)` → returns SSE URL string (used by T19)
- `src/composables/usePayment.js` — reactive form + submission:
  - `form` ref: `{ sourceAccount, destinationAccount, amount, currency, type }` (type: 'INSTANT'|'STANDARD')
  - `errors` ref: per-field validation (non-empty, amount > 0)
  - `submit()` → validate → `initiatePayment()` → navigate to `/payments/:txId/status`; on failure sets `errorMessage`
  - `isLoading`, `errorMessage` refs
- `src/views/PaymentsView.vue` — styled payment form matching reference design (card wrapper, red button, inline errors, loading spinner on submit)

## Unit Tests

`src/composables/__tests__/usePayment.spec.js`:
- Submit valid form → `initiatePayment` called with correct payload
- Submit with empty `sourceAccount` → `errors.sourceAccount` non-empty, `initiatePayment` not called
- Submit with `amount = -5` → `errors.amount` non-empty
- API rejects → `errorMessage` set, navigation not triggered

`src/views/__tests__/PaymentsView.spec.js`:
- Mount component; fill all fields; click submit → assert `initiatePayment` called once with matching payload

## Verification
```bash
cd apps/spa-mobile-app
npm test        # usePayment.spec.js and PaymentsView.spec.js pass
npm run dev     # form renders at /payments with correct styling
```

## Acceptance Criteria
- [ ] All 5 test cases pass
- [ ] `npm test` exits 0
- [ ] Form matches reference: white card, red accents, focus ring, inline errors, loading state
- [ ] Form renders without console errors in dev server
