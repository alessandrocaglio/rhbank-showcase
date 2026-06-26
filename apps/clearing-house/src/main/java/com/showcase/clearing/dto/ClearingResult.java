package com.showcase.clearing.dto;

public record ClearingResult(
        String transactionId,
        String status,
        String detail,
        String clearedAt
) {}
