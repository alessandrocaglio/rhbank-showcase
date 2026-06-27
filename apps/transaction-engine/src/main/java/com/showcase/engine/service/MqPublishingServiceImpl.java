package com.showcase.engine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.showcase.engine.domain.TransactionLedger;
import com.showcase.engine.dto.ClearingMessagePayload;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.JmsException;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Service
public class MqPublishingServiceImpl implements MqPublishingService {

    private static final Logger log = LoggerFactory.getLogger(MqPublishingServiceImpl.class);

    private final JmsTemplate jmsTemplate;
    private final ObjectMapper objectMapper;
    private final String clearingQueue;

    public MqPublishingServiceImpl(
            JmsTemplate jmsTemplate,
            ObjectMapper objectMapper,
            @Value("${app.mq.queues.clearing}") String clearingQueue) {
        this.jmsTemplate = jmsTemplate;
        this.objectMapper = objectMapper;
        this.clearingQueue = clearingQueue;
    }

    @Override
    @Retryable(
        retryFor = { JmsException.class, RuntimeException.class },
        maxAttempts = 5,
        backoff = @Backoff(delay = 1000, multiplier = 2, maxDelay = 30_000)
    )
    public void publishToClearingQueue(TransactionLedger ledger) {
        ClearingMessagePayload payload = new ClearingMessagePayload(
                ledger.getTransactionId(),
                ledger.getSourceAccount(),
                ledger.getDestinationAccount(),
                ledger.getAmount(),
                ledger.getCurrency(),
                ledger.getStatus(),
                Instant.now().toString());

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            Span.current().recordException(ex);
            throw new RuntimeException("Failed to serialize clearing payload", ex);
        }

        String traceparent = extractTraceparent();

        jmsTemplate.send(clearingQueue, session -> {
            jakarta.jms.TextMessage message = session.createTextMessage(json);
            message.setStringProperty("traceparent", traceparent);
            message.setStringProperty("transactionId", ledger.getTransactionId());
            return message;
        });

        log.info("Published clearing message for transactionId={}", ledger.getTransactionId());
    }

    @Recover
    public void recoverPublishToClearingQueue(Exception ex, TransactionLedger ledger) {
        log.error("All MQ publish retries exhausted for transactionId={}. Payment is stuck in PENDING state. Manual intervention required.",
                  ledger.getTransactionId(), ex);
        // In production this would alert PagerDuty / write to a dead-letter store.
    }

    private String extractTraceparent() {
        Map<String, String> carrier = new HashMap<>();
        GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .inject(Context.current(), carrier, Map::put);
        return carrier.getOrDefault("traceparent", "");
    }
}
