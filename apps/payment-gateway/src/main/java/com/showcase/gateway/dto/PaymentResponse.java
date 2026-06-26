package com.showcase.gateway.dto;

public record PaymentResponse(String transactionId, String status, String message) {}
