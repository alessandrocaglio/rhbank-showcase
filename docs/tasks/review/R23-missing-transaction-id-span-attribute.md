# R23 — 🟡 payment-gateway: `transactionId` missing from Kafka consumer and SSE spans

## Problem
The showcase's primary goal is demonstrating unbroken end-to-end trace propagation. Two spans in
`payment-gateway` are missing the `bank.payment.transaction_id` attribute that ties them to the
rest of the payment lifecycle:

1. `PaymentCompletedListener.java` — after deserialising the `PaymentCompletedEvent`, `event.transactionId()`
   is available but never added as a span attribute. The Kafka consumer span is disconnected from
   the payment trace in Grafana Tempo.

2. `PaymentController.streamPaymentStatus()` — the SSE subscription span has no `transactionId`
   attribute. If a client subscribes but never receives the event, there is nothing in the trace
   to correlate the subscription with the payment.

## Files to change
- `apps/payment-gateway/src/main/java/com/showcase/gateway/messaging/PaymentCompletedListener.java`
- `apps/payment-gateway/src/main/java/com/showcase/gateway/controller/PaymentController.java`

## Fix
```java
// PaymentCompletedListener.java — after deserialisation
Span.current().setAttribute("bank.payment.transaction_id", event.transactionId());

// PaymentController.java — in streamPaymentStatus()
Span.current().setAttribute("bank.payment.transaction_id", transactionId);
```

Also add `transactionId` to MDC in the listener so all log lines within the Kafka processing
carry the transaction context:
```java
MDC.put("transactionId", event.transactionId());
try { ... } finally { MDC.remove("transactionId"); }
```

## Acceptance
- Grafana Tempo trace for a payment shows `bank.payment.transaction_id` on the Kafka consumer span
- All spans in the trace can be filtered/found by transaction ID
