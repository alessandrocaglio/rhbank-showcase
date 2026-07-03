package com.showcase.verifier.service;

import com.showcase.verifier.domain.Account;
import com.showcase.verifier.outbox.OutboxMessage;
import com.showcase.verifier.outbox.OutboxRepository;
import com.showcase.verifier.repository.AccountRepository;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@QuarkusTest
class AccountVerificationServiceTest {

    @InjectMock
    AccountRepository accountRepository;

    @InjectMock
    OutboxRepository outboxRepository;

    @Inject
    AccountVerificationService service;

    private Account createAccount(String id, String name, BigDecimal balance, String status) {
        try {
            var constructor = Account.class.getDeclaredConstructor(
                    String.class, String.class, BigDecimal.class, String.class);
            constructor.setAccessible(true);
            return constructor.newInstance(id, name, balance, status);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create Account via reflection", e);
        }
    }

    @Test
    void shouldApproveWhenBalanceIsSufficient() {
        Account account = createAccount("ACC-001", "Alice Martin", new BigDecimal("1000.00"), "ACTIVE");
        when(accountRepository.findByAccountIdForUpdate("ACC-001")).thenReturn(Optional.of(account));

        var result = service.verify("TXN-001", "ACC-001", "ACC-002", new BigDecimal("500.00"), "USD");

        assertTrue(result.approved());
        assertEquals("", result.reason());
    }

    @Test
    void shouldRejectWhenBalanceIsInsufficient() {
        Account account = createAccount("ACC-001", "Alice Martin", new BigDecimal("100.00"), "ACTIVE");
        when(accountRepository.findByAccountIdForUpdate("ACC-001")).thenReturn(Optional.of(account));

        var result = service.verify("TXN-002", "ACC-001", "ACC-002", new BigDecimal("500.00"), "USD");

        assertFalse(result.approved());
        assertTrue(result.reason().contains("Insufficient"), "Expected reason to contain 'Insufficient' but was: " + result.reason());
    }

    @Test
    void shouldRejectWhenAccountIsSuspended() {
        Account account = createAccount("ACC-004", "Diana Prince", new BigDecimal("25000.00"), "SUSPENDED");
        when(accountRepository.findByAccountIdForUpdate("ACC-004")).thenReturn(Optional.of(account));

        var result = service.verify("TXN-003", "ACC-004", "ACC-002", new BigDecimal("100.00"), "USD");

        assertFalse(result.approved());
        assertTrue(result.reason().contains("not active"), "Expected reason to contain 'not active' but was: " + result.reason());
    }

    @Test
    void shouldRejectWhenAccountNotFound() {
        when(accountRepository.findByAccountIdForUpdate("ACC-999")).thenReturn(Optional.empty());

        var result = service.verify("TXN-004", "ACC-999", "ACC-002", new BigDecimal("100.00"), "USD");

        assertFalse(result.approved());
        assertTrue(result.reason().contains("not found"), "Expected reason to contain 'not found' but was: " + result.reason());
    }

    @Test
    void shouldPersistOutboxEntryWhenApproved() {
        Account account = createAccount("ACC-001", "Alice Martin", new BigDecimal("1000.00"), "ACTIVE");
        when(accountRepository.findByAccountIdForUpdate("ACC-001")).thenReturn(Optional.of(account));

        service.verify("txn-001", "ACC-001", "ACC-002", new BigDecimal("150.00"), "USD");

        verify(outboxRepository).persist(any(OutboxMessage.class));
    }

    @Test
    void shouldPersistOutboxEntryWithNullTraceparentWhenNoOtelContextActive() {
        Account account = createAccount("ACC-001", "Alice Martin", new BigDecimal("1000.00"), "ACTIVE");
        when(accountRepository.findByAccountIdForUpdate("ACC-001")).thenReturn(Optional.of(account));

        service.verify("txn-ctx-null", "ACC-001", "ACC-002", new BigDecimal("100.00"), "USD");

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository).persist(captor.capture());
        assertNull(captor.getValue().traceparent,
            "traceparent must be null when no OTel context is active in the test environment");
    }

    @Test
    void shouldNotPersistOutboxEntryWhenBalanceInsufficient() {
        Account account = createAccount("ACC-001", "Alice Martin", new BigDecimal("10.00"), "ACTIVE");
        when(accountRepository.findByAccountIdForUpdate("ACC-001")).thenReturn(Optional.of(account));

        service.verify("txn-002", "ACC-001", "ACC-002", new BigDecimal("500.00"), "USD");

        verifyNoInteractions(outboxRepository);
    }

    @Test
    void shouldNotPersistOutboxEntryWhenAccountSuspended() {
        Account account = createAccount("ACC-004", "Diana Prince", new BigDecimal("25000.00"), "SUSPENDED");
        when(accountRepository.findByAccountIdForUpdate("ACC-004")).thenReturn(Optional.of(account));

        service.verify("txn-003", "ACC-004", "ACC-002", new BigDecimal("100.00"), "USD");

        verifyNoInteractions(outboxRepository);
    }

    @Test
    void shouldRejectWhenAmountIsNegative() {
        var result = service.verify("TXN-NEG", "ACC-001", "ACC-002", new BigDecimal("-50.00"), "USD");
        assertFalse(result.approved());
        assertTrue(result.reason().contains("positive"), "Expected 'positive' in: " + result.reason());
    }

    @Test
    void shouldRejectWhenAmountIsZero() {
        var result = service.verify("TXN-ZERO", "ACC-001", "ACC-002", BigDecimal.ZERO, "USD");
        assertFalse(result.approved());
        assertTrue(result.reason().contains("positive"), "Expected 'positive' in: " + result.reason());
    }

    @Test
    void shouldRejectWhenSourceAccountIsBlank() {
        var result = service.verify("TXN-BLANK", "", "ACC-002", new BigDecimal("100.00"), "USD");
        assertFalse(result.approved());
        assertTrue(result.reason().contains("required"), "Expected 'required' in: " + result.reason());
    }

    @Test
    void shouldRejectSelfTransfer() {
        var result = service.verify("TXN-SELF", "ACC-001", "ACC-001", new BigDecimal("100.00"), "USD");
        assertFalse(result.approved());
        assertTrue(result.reason().contains("differ"), "Expected 'differ' in: " + result.reason());
    }
}
