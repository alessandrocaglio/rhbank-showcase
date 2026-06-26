package com.showcase.clearing.messaging;

import com.showcase.clearing.dto.ClearingResult;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySink;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class PaymentCompletedPublisherImplTest {

    @Inject
    PaymentCompletedPublisher publisher;

    @Inject
    @Any
    InMemoryConnector connector;

    @Test
    void publish_sendsCompletedEventToChannel() {
        connector.sink("payment-completed").clear();
        var result = new ClearingResult("txn-001", "COMPLETED", "Clearing successful", "2024-01-15T10:30:00Z");
        publisher.publish(result);
        InMemorySink<String> sink = connector.sink("payment-completed");
        assertEquals(1, sink.received().size());
        String payload = sink.received().get(0).getPayload();
        assertTrue(payload.contains("txn-001"));
        assertTrue(payload.contains("COMPLETED"));
    }

    @Test
    void publish_sendsFailedEventToChannel() {
        connector.sink("payment-completed").clear();
        var result = new ClearingResult("txn-002", "FAILED", "Clearing rejected", "2024-01-15T10:30:01Z");
        publisher.publish(result);
        InMemorySink<String> sink = connector.sink("payment-completed");
        assertEquals(1, sink.received().size());
        assertTrue(sink.received().get(0).getPayload().contains("FAILED"));
    }
}
