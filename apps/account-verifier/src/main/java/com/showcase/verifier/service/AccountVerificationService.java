package com.showcase.verifier.service;

import com.showcase.verifier.dto.PaymentApprovedEvent;
import com.showcase.verifier.dto.VerificationResult;
import com.showcase.verifier.repository.AccountRepository;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;

@ApplicationScoped
public class AccountVerificationService {

    private final AccountRepository accountRepository;
    private final PaymentEventPublisher paymentEventPublisher;

    @Inject
    public AccountVerificationService(AccountRepository accountRepository,
                                      PaymentEventPublisher paymentEventPublisher) {
        this.accountRepository = accountRepository;
        this.paymentEventPublisher = paymentEventPublisher;
    }

    @Transactional
    public VerificationResult verify(String transactionId,
                                     String sourceAccount,
                                     String destinationAccount,
                                     BigDecimal amount,
                                     String currency) {
        Span span = Span.current();
        span.setAttribute("bank.payment.transaction_id", transactionId);
        span.setAttribute("bank.account.source", sourceAccount);

        try {
            var accountOpt = accountRepository.findByIdOptional(sourceAccount);
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
            paymentEventPublisher.publishApproved(event);

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
