package com.showcase.engine.repository;

import com.showcase.engine.domain.TransactionLedger;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionLedgerRepository extends JpaRepository<TransactionLedger, String> {
}
