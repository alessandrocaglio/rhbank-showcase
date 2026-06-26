package com.showcase.engine.dto;

import java.math.BigDecimal;

public record PaymentApprovedEvent(
        String transactionId,
        String sourceAccount,
        String destinationAccount,
        BigDecimal amount,
        String currency,
        String approvedAt
) {}
