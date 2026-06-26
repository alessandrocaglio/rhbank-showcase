package com.showcase.clearing.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.showcase.clearing.dto.ClearingResult;
import com.showcase.clearing.dto.PaymentCompletedEvent;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class PaymentCompletedPublisherImpl implements PaymentCompletedPublisher {

    private final Emitter<String> emitter;
    private final ObjectMapper objectMapper;

    @Inject
    public PaymentCompletedPublisherImpl(
            @Channel("payment-completed") Emitter<String> emitter,
            ObjectMapper objectMapper) {
        this.emitter = emitter;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(ClearingResult result) {
        PaymentCompletedEvent event = new PaymentCompletedEvent(
                result.transactionId(),
                result.status(),
                result.clearedAt(),
                result.detail()
        );
        try {
            String json = objectMapper.writeValueAsString(event);
            emitter.send(json);
        } catch (Exception ex) {
            Span.current().setStatus(StatusCode.ERROR, ex.getMessage());
            Span.current().recordException(ex);
            throw new RuntimeException("Failed to publish payment-completed event", ex);
        }
    }
}
