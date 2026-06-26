package com.showcase.engine.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.showcase.engine.domain.TransactionLedger;
import com.showcase.engine.dto.PaymentApprovedEvent;
import com.showcase.engine.service.LedgerService;
import com.showcase.engine.service.MqPublishingService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
public class PaymentApprovedListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentApprovedListener.class);

    private final LedgerService ledgerService;
    private final MqPublishingService mqPublishingService;
    private final ObjectMapper objectMapper;

    public PaymentApprovedListener(
            LedgerService ledgerService,
            MqPublishingService mqPublishingService,
            ObjectMapper objectMapper) {
        this.ledgerService = ledgerService;
        this.mqPublishingService = mqPublishingService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${app.kafka.topics.payment-approved:payment-approved}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onPaymentApproved(
            @Payload String payload,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.info("Received message on topic {}", topic);
        try {
            PaymentApprovedEvent event = objectMapper.readValue(payload, PaymentApprovedEvent.class);
            TransactionLedger savedLedger = ledgerService.persistLedgerRecord(event);
            mqPublishingService.publishToClearingQueue(savedLedger);
        } catch (Exception ex) {
            Span span = Span.current();
            span.setStatus(StatusCode.ERROR, ex.getMessage());
            span.recordException(ex);
            throw new RuntimeException(ex);
        }
    }
}
