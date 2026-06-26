package com.showcase.gateway.dto;

public record PaymentStatusEvent(
        String transactionId,
        String status,
        String timestamp,
        String detail
) {}
