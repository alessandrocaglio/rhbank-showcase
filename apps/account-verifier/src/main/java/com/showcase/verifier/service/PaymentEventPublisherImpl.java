package com.showcase.verifier.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.showcase.verifier.dto.PaymentApprovedEvent;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.smallrye.reactive.messaging.MutinyEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Channel;

@ApplicationScoped
public class PaymentEventPublisherImpl implements PaymentEventPublisher {

    private final MutinyEmitter<String> emitter;
    private final ObjectMapper objectMapper;

    @Inject
    public PaymentEventPublisherImpl(
            @Channel("payment-approved") MutinyEmitter<String> emitter,
            ObjectMapper objectMapper) {
        this.emitter = emitter;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public void publishApproved(PaymentApprovedEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            emitter.sendAndAwait(json);
        } catch (Exception ex) {
            Span.current().setStatus(StatusCode.ERROR, ex.getMessage());
            Span.current().recordException(ex);
            throw new RuntimeException("Failed to publish payment-approved event", ex);
        }
    }
}
