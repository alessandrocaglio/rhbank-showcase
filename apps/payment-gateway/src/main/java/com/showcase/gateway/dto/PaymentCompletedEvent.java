package com.showcase.gateway.dto;

public record PaymentCompletedEvent(
        String transactionId,
        String status,
        String clearedAt,
        String detail
) {}
