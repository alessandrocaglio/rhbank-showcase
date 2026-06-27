package com.showcase.verifier.repository;

import com.showcase.verifier.domain.Account;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AccountRepositoryTest {

    @Inject
    AccountRepository accountRepository;

    @Test
    @Transactional
    void findByIdReturnsAliceMartin() {
        Optional<Account> result = accountRepository.findByIdOptional("ACC-001");
        assertTrue(result.isPresent());
        assertEquals("ACC-001", result.get().getAccountId());
        assertEquals("Alice Martin", result.get().getCustomerName());
    }

    @Test
    @Transactional
    void findByIdReturnsSuspendedStatus() {
        Optional<Account> result = accountRepository.findByIdOptional("ACC-004");
        assertTrue(result.isPresent());
        assertEquals("SUSPENDED", result.get().getStatus());
    }

    @Test
    @Transactional
    void findByIdReturnsEmptyForUnknownAccount() {
        Optional<Account> result = accountRepository.findByIdOptional("ACC-999");
        assertFalse(result.isPresent());
    }

    @Test
    @Transactional
    void findByIdReturnsCorrectBalance() {
        Optional<Account> result = accountRepository.findByIdOptional("ACC-001");
        assertTrue(result.isPresent());
        assertEquals(0, new BigDecimal("10000.00").compareTo(result.get().getBalance()));
    }

    @Test
    @Transactional
    void findByAccountIdForUpdate_returnsAccount() {
        Optional<Account> result = accountRepository.findByAccountIdForUpdate("ACC-001");
        assertTrue(result.isPresent());
        assertEquals("ACC-001", result.get().getAccountId());
    }
}
