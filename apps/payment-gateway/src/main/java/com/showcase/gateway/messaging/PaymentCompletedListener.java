package com.showcase.gateway.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.showcase.gateway.dto.PaymentCompletedEvent;
import com.showcase.gateway.dto.PaymentStatusEvent;
import com.showcase.gateway.service.SseEmitterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
public class PaymentCompletedListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentCompletedListener.class);

    private final SseEmitterService sseEmitterService;
    private final ObjectMapper objectMapper;

    public PaymentCompletedListener(SseEmitterService sseEmitterService, ObjectMapper objectMapper) {
        this.sseEmitterService = sseEmitterService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${app.kafka.topics.payment-completed:payment-completed}",
                   groupId = "${spring.kafka.consumer.group-id}")
    public void onPaymentCompleted(@Payload String payload) {
        try {
            PaymentCompletedEvent event = objectMapper.readValue(payload, PaymentCompletedEvent.class);

            PaymentStatusEvent sseEvent = new PaymentStatusEvent(
                    event.transactionId(),
                    event.status(),
                    event.clearedAt(),
                    event.detail()
            );

            sseEmitterService.resolve(event.transactionId(), sseEvent);
        } catch (Exception ex) {
            io.opentelemetry.api.trace.Span.current().recordException(ex);
            log.error("Failed to process payment-completed event: {}", ex.getMessage(), ex);
            throw new RuntimeException(ex);
        }
    }
}
