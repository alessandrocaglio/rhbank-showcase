package com.showcase.engine.service;

import com.showcase.engine.domain.TransactionLedger;
import com.showcase.engine.dto.PaymentApprovedEvent;
import com.showcase.engine.repository.TransactionLedgerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    private TransactionLedgerRepository repository;

    @InjectMocks
    private LedgerService ledgerService;

    @Test
    void shouldPersistLedgerRecordWithCorrectFieldsWhenEventIsValid() {
        PaymentApprovedEvent event = new PaymentApprovedEvent(
                "txn-100",
                "ACC-001",
                "ACC-002",
                new BigDecimal("250.00"),
                "USD",
                "2024-01-15T10:30:00Z");

        when(repository.save(any(TransactionLedger.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TransactionLedger result = ledgerService.persistLedgerRecord(event);

        verify(repository, times(1)).save(any(TransactionLedger.class));
        assertThat(result.getTransactionId()).isEqualTo("txn-100");
        assertThat(result.getSourceAccount()).isEqualTo("ACC-001");
        assertThat(result.getDestinationAccount()).isEqualTo("ACC-002");
        assertThat(result.getAmount()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(result.getCurrency()).isEqualTo("USD");
        assertThat(result.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void shouldPropagateExceptionWhenRepositorySaveThrows() {
        PaymentApprovedEvent event = new PaymentApprovedEvent(
                "txn-fail",
                "ACC-001",
                "ACC-002",
                new BigDecimal("100.00"),
                "USD",
                "2024-01-15T10:30:00Z");

        when(repository.save(any(TransactionLedger.class)))
                .thenThrow(new RuntimeException("DB connection lost"));

        assertThatThrownBy(() -> ledgerService.persistLedgerRecord(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB connection lost");
    }
}
