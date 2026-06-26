package com.showcase.engine.dto;

import java.math.BigDecimal;

public record ClearingMessagePayload(
        String transactionId,
        String sourceAccount,
        String destinationAccount,
        BigDecimal amount,
        String currency,
        String status,
        String sentAt
) {}
