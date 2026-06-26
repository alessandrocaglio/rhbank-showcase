package com.showcase.clearing.dto;

public record PaymentCompletedEvent(
        String transactionId,
        String status,
        String clearedAt,
        String detail
) {}
