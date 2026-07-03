package com.showcase.verifier.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.showcase.verifier.dto.PaymentApprovedEvent;
import com.showcase.verifier.dto.VerificationResult;
import com.showcase.verifier.outbox.OutboxMessage;
import com.showcase.verifier.outbox.OutboxRepository;
import com.showcase.verifier.repository.AccountRepository;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class AccountVerificationService {

    private final AccountRepository accountRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Inject
    public AccountVerificationService(AccountRepository accountRepository,
                                      OutboxRepository outboxRepository,
                                      ObjectMapper objectMapper) {
        this.accountRepository = accountRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public VerificationResult verify(String transactionId,
                                     String sourceAccount,
                                     String destinationAccount,
                                     BigDecimal amount,
                                     String currency) {
        Span span = Span.current();

        // Input validation — reject before any DB access
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            span.setAttribute("bank.account.approved", false);
            return VerificationResult.ofRejected("Amount must be positive");
        }
        if (sourceAccount == null || sourceAccount.isBlank()) {
            span.setAttribute("bank.account.approved", false);
            return VerificationResult.ofRejected("Source account is required");
        }
        if (destinationAccount == null || destinationAccount.isBlank()) {
            span.setAttribute("bank.account.approved", false);
            return VerificationResult.ofRejected("Destination account is required");
        }
        if (sourceAccount.equals(destinationAccount)) {
            span.setAttribute("bank.account.approved", false);
            return VerificationResult.ofRejected("Source and destination accounts must differ");
        }

        span.setAttribute("bank.payment.transaction_id", transactionId);
        span.setAttribute("bank.account.source", sourceAccount);

        try {
            var accountOpt = accountRepository.findByAccountIdForUpdate(sourceAccount);
            if (accountOpt.isEmpty()) {
                VerificationResult result = VerificationResult.ofRejected("Account not found: " + sourceAccount);
                span.setAttribute("bank.account.approved", result.approved());
                return result;
            }

            var account = accountOpt.get();

            if (!"ACTIVE".equals(account.getStatus())) {
                VerificationResult result = VerificationResult.ofRejected("Account is not active: " + sourceAccount);
                span.setAttribute("bank.account.approved", result.approved());
                return result;
            }

            if (account.getBalance().compareTo(amount) < 0) {
                VerificationResult result = VerificationResult.ofRejected("Insufficient balance");
                span.setAttribute("bank.account.approved", result.approved());
                return result;
            }

            // Apply hold: deduct amount from balance atomically within this transaction
            accountRepository.update("balance = balance - ?1 where accountId = ?2", amount, sourceAccount);

            PaymentApprovedEvent event = new PaymentApprovedEvent(
                    transactionId,
                    sourceAccount,
                    destinationAccount,
                    amount,
                    currency,
                    java.time.Instant.now().toString()
            );

            // Write outbox entry atomically with the balance deduction — no Kafka call here.
            // The OutboxPoller will read this row and publish to Kafka asynchronously.
            try {
                String payload = objectMapper.writeValueAsString(event);
                Map<String, String> propagationCarrier = new HashMap<>();
                GlobalOpenTelemetry.getPropagators()
                    .getTextMapPropagator()
                    .inject(Context.current(), propagationCarrier, Map::put);
                String traceparent = propagationCarrier.get("traceparent");
                String tracestate  = propagationCarrier.get("tracestate");
                outboxRepository.persist(OutboxMessage.of("payment-approved", payload, traceparent, tracestate));
            } catch (Exception ex) {
                throw new RuntimeException("Failed to serialize payment event to outbox", ex);
            }

            VerificationResult result = VerificationResult.ofApproved();
            span.setAttribute("bank.account.approved", result.approved());
            return result;

        } catch (Exception ex) {
            span.setStatus(StatusCode.ERROR, ex.getMessage());
            span.recordException(ex);
            throw ex;
        }
    }
}
