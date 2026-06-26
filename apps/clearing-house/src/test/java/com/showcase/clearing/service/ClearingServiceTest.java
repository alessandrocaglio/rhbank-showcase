package com.showcase.clearing.service;

import com.showcase.clearing.dto.ClearingResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClearingServiceTest {

    // Zero delay: tests run in milliseconds, not seconds.
    // Production default (100–500ms) is configured via application.properties.
    private final ClearingService service = new ClearingService(0, 0);

    @Test
    void process_returnsNonNullResult() {
        ClearingResult result = service.process("txn-001");
        assertNotNull(result);
        assertEquals("txn-001", result.transactionId());
        assertNotNull(result.clearedAt());
        assertTrue(result.status().equals("COMPLETED") || result.status().equals("FAILED"));
    }

    @Test
    void process_hasNonNullDetail() {
        ClearingResult result = service.process("txn-002");
        assertNotNull(result.detail());
        assertFalse(result.detail().isBlank());
    }

    @Test
    void process_statisticallyApproves95Percent() {
        long completed = 0;
        for (int i = 0; i < 200; i++) {
            if ("COMPLETED".equals(service.process("txn-" + i).status())) completed++;
        }
        // 95% ± 10% tolerance over 200 runs (no delay — runs in ~50ms total)
        assertTrue(completed >= 170, "Expected ≥170 COMPLETED out of 200, got: " + completed);
        assertTrue(completed <= 200, "Expected ≤200 COMPLETED, got: " + completed);
    }
}
