package com.showcase.verifier.service;

import com.showcase.verifier.dto.PaymentApprovedEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySink;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class PaymentEventPublisherImplTest {

    @Inject
    PaymentEventPublisher publisher;

    @Inject
    @Any
    InMemoryConnector connector;

    @BeforeEach
    void clearSink() {
        connector.sink("payment-approved").clear();
    }

    @Test
    void publishApproved_sendsJsonToChannel() {
        var event = new PaymentApprovedEvent(
                "txn-001", "ACC-001", "ACC-002",
                new BigDecimal("150.00"), "USD",
                Instant.now().toString());

        publisher.publishApproved(event);

        InMemorySink<String> sink = connector.sink("payment-approved");
        assertEquals(1, sink.received().size());
        String payload = sink.received().get(0).getPayload();
        assertTrue(payload.contains("txn-001"), "Payload should contain transactionId");
        assertTrue(payload.contains("ACC-001"), "Payload should contain sourceAccount");
        assertTrue(payload.contains("ACC-002"), "Payload should contain destinationAccount");
        assertTrue(payload.contains("150.00") || payload.contains("150"), "Payload should contain amount");
        assertTrue(payload.contains("USD"), "Payload should contain currency");
    }
}
