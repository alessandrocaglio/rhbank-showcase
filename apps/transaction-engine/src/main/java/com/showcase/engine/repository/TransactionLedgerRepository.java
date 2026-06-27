package com.showcase.engine.repository;

import com.showcase.engine.domain.TransactionLedger;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TransactionLedgerRepository extends JpaRepository<TransactionLedger, String> {

    boolean existsByTransactionId(String transactionId);

    Optional<TransactionLedger> findByTransactionId(String transactionId);
}
