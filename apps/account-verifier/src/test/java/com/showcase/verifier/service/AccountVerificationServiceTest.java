package com.showcase.verifier.service;

import com.showcase.verifier.domain.Account;
import com.showcase.verifier.dto.PaymentApprovedEvent;
import com.showcase.verifier.repository.AccountRepository;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@QuarkusTest
class AccountVerificationServiceTest {

    @InjectMock
    AccountRepository accountRepository;

    @InjectMock
    PaymentEventPublisher paymentEventPublisher;

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
        when(accountRepository.findByIdOptional("ACC-001")).thenReturn(Optional.of(account));

        var result = service.verify("TXN-001", "ACC-001", "ACC-002", new BigDecimal("500.00"), "USD");

        assertTrue(result.approved());
        assertEquals("", result.reason());
    }

    @Test
    void shouldRejectWhenBalanceIsInsufficient() {
        Account account = createAccount("ACC-001", "Alice Martin", new BigDecimal("100.00"), "ACTIVE");
        when(accountRepository.findByIdOptional("ACC-001")).thenReturn(Optional.of(account));

        var result = service.verify("TXN-002", "ACC-001", "ACC-002", new BigDecimal("500.00"), "USD");

        assertFalse(result.approved());
        assertTrue(result.reason().contains("Insufficient"), "Expected reason to contain 'Insufficient' but was: " + result.reason());
    }

    @Test
    void shouldRejectWhenAccountIsSuspended() {
        Account account = createAccount("ACC-004", "Diana Prince", new BigDecimal("25000.00"), "SUSPENDED");
        when(accountRepository.findByIdOptional("ACC-004")).thenReturn(Optional.of(account));

        var result = service.verify("TXN-003", "ACC-004", "ACC-002", new BigDecimal("100.00"), "USD");

        assertFalse(result.approved());
        assertTrue(result.reason().contains("not active"), "Expected reason to contain 'not active' but was: " + result.reason());
    }

    @Test
    void shouldRejectWhenAccountNotFound() {
        when(accountRepository.findByIdOptional("ACC-999")).thenReturn(Optional.empty());

        var result = service.verify("TXN-004", "ACC-999", "ACC-002", new BigDecimal("100.00"), "USD");

        assertFalse(result.approved());
        assertTrue(result.reason().contains("not found"), "Expected reason to contain 'not found' but was: " + result.reason());
    }

    @Test
    void shouldPublishEventWhenApproved() {
        Account account = createAccount("ACC-001", "Alice Martin", new BigDecimal("1000.00"), "ACTIVE");
        when(accountRepository.findByIdOptional("ACC-001")).thenReturn(Optional.of(account));

        service.verify("txn-001", "ACC-001", "ACC-002", new BigDecimal("150.00"), "USD");

        verify(paymentEventPublisher).publishApproved(
                argThat(e -> "txn-001".equals(e.transactionId())));
    }

    @Test
    void shouldNotPublishEventWhenBalanceInsufficient() {
        Account account = createAccount("ACC-001", "Alice Martin", new BigDecimal("10.00"), "ACTIVE");
        when(accountRepository.findByIdOptional("ACC-001")).thenReturn(Optional.of(account));

        service.verify("txn-002", "ACC-001", "ACC-002", new BigDecimal("500.00"), "USD");

        verifyNoInteractions(paymentEventPublisher);
    }

    @Test
    void shouldNotPublishEventWhenAccountSuspended() {
        Account account = createAccount("ACC-004", "Diana Prince", new BigDecimal("25000.00"), "SUSPENDED");
        when(accountRepository.findByIdOptional("ACC-004")).thenReturn(Optional.of(account));

        service.verify("txn-003", "ACC-004", "ACC-002", new BigDecimal("100.00"), "USD");

        verifyNoInteractions(paymentEventPublisher);
    }
}
