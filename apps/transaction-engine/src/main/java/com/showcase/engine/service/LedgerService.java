package com.showcase.engine.service;

import com.showcase.engine.domain.TransactionLedger;
import com.showcase.engine.dto.PaymentApprovedEvent;
import com.showcase.engine.repository.TransactionLedgerRepository;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final TransactionLedgerRepository repository;

    public LedgerService(TransactionLedgerRepository repository) {
        this.repository = repository;
    }

    public TransactionLedger persistLedgerRecord(PaymentApprovedEvent event) {
        MDC.put("transactionId", event.transactionId());

        Optional<TransactionLedger> existing = repository.findByTransactionId(event.transactionId());
        if (existing.isPresent()) {
            log.warn("Duplicate payment-approved for txId={}, reusing existing ledger record", event.transactionId());
            return existing.get();
        }

        log.info("Persisting ledger record for transaction {}", event.transactionId());

        TransactionLedger ledger = TransactionLedger.create(
                event.transactionId(),
                event.sourceAccount(),
                event.destinationAccount(),
                event.amount(),
                event.currency(),
                "PENDING");

        TransactionLedger saved = repository.save(ledger);

        Span.current().setAttribute("bank.payment.transaction_id", event.transactionId());
        Span.current().setAttribute("bank.ledger.record_id", saved.getTransactionId());

        log.info("Ledger record persisted for transaction {}", saved.getTransactionId());
        return saved;
    }
}
