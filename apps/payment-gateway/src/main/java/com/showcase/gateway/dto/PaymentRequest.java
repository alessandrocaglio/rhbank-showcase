package com.showcase.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record PaymentRequest(
        @NotBlank(message = "Source account is required") String sourceAccount,
        @NotBlank(message = "Destination account is required") String destinationAccount,
        @NotNull @Positive(message = "Amount must be positive") BigDecimal amount,
        @NotBlank(message = "Currency is required") String currency
) {}
