package com.showcase.engine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "transaction_ledger")
public class TransactionLedger {

    @Id
    @Column(name = "transaction_id")
    private String transactionId;

    @Column(name = "source_account", nullable = false)
    private String sourceAccount;

    @Column(name = "destination_account", nullable = false)
    private String destinationAccount;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected TransactionLedger() {
        // JPA no-arg constructor
    }

    TransactionLedger(
            String transactionId,
            String sourceAccount,
            String destinationAccount,
            BigDecimal amount,
            String currency,
            String status,
            LocalDateTime createdAt) {
        this.transactionId = transactionId;
        this.sourceAccount = sourceAccount;
        this.destinationAccount = destinationAccount;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.createdAt = createdAt;
    }

    public static TransactionLedger create(
            String transactionId,
            String sourceAccount,
            String destinationAccount,
            BigDecimal amount,
            String currency,
            String status) {
        return new TransactionLedger(
                transactionId,
                sourceAccount,
                destinationAccount,
                amount,
                currency,
                status,
                LocalDateTime.now());
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getSourceAccount() {
        return sourceAccount;
    }

    public String getDestinationAccount() {
        return destinationAccount;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
