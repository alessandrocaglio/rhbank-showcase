package com.showcase.verifier.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the Account entity.
 * This test lives in the domain package to access the package-private constructor.
 * JPA uses the protected no-arg constructor via reflection; the package-private
 * constructor is the factory entry point used by domain/service code.
 */
class AccountTest {

    @Test
    void constructor_setsAllFields() {
        var account = new Account("ACC-TEST", "Test User", new BigDecimal("500.00"), "ACTIVE");

        assertEquals("ACC-TEST", account.getAccountId());
        assertEquals("Test User", account.getCustomerName());
        assertEquals(0, new BigDecimal("500.00").compareTo(account.getBalance()));
        assertEquals("ACTIVE", account.getStatus());
    }

    @Test
    void constructor_allowsSuspendedStatus() {
        var account = new Account("ACC-SUSP", "Suspended User", BigDecimal.ZERO, "SUSPENDED");

        assertEquals("SUSPENDED", account.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(account.getBalance()));
    }
}
