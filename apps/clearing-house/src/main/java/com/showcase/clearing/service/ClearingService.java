package com.showcase.clearing.service;

import com.showcase.clearing.dto.ClearingResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class ClearingService {

    private static final double SUCCESS_RATE = 0.95;

    private final int minDelayMs;
    private final int maxDelayMs;

    @Inject
    public ClearingService(
            @ConfigProperty(name = "clearing.simulation.delay-ms.min", defaultValue = "100") int minDelayMs,
            @ConfigProperty(name = "clearing.simulation.delay-ms.max", defaultValue = "500") int maxDelayMs) {
        this.minDelayMs = minDelayMs;
        this.maxDelayMs = maxDelayMs;
    }

    public ClearingResult process(String transactionId) {
        simulateNetworkDelay();
        boolean success = Math.random() < SUCCESS_RATE;
        String now = java.time.Instant.now().toString();
        if (success) {
            return new ClearingResult(transactionId, "COMPLETED", "Clearing successful", now);
        } else {
            return new ClearingResult(transactionId, "FAILED", "Clearing rejected by counterparty", now);
        }
    }

    private void simulateNetworkDelay() {
        if (maxDelayMs == 0) {
            return;
        }
        try {
            int range = maxDelayMs - minDelayMs;
            long delay = minDelayMs + (long) (Math.random() * range);
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
