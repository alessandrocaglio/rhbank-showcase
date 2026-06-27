package com.showcase.engine.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.showcase.engine.domain.TransactionLedger;
import com.showcase.engine.dto.PaymentApprovedEvent;
import com.showcase.engine.service.LedgerService;
import com.showcase.engine.service.MqPublishingService;
import jakarta.jms.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"payment-approved", "payment-approved.DLT"})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.properties.hibernate.dialect=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.jms.cache.enabled=false",
        "ibm.mq.conn-name=localhost(1414)"
})
class PaymentApprovedListenerTest {

    @MockBean
    private LedgerService ledgerService;

    @MockBean
    private MqPublishingService mqPublishingService;

    // Prevent IBM MQ auto-configuration from attempting a real broker connection
    @MockBean
    private ConnectionFactory connectionFactory;

    @MockBean(name = "jmsTemplate")
    private JmsTemplate jmsTemplate;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldCallLedgerServiceWithCorrectTransactionIdOnValidPayload() throws Exception {
        PaymentApprovedEvent event = new PaymentApprovedEvent(
                "txn-listener-001",
                "ACC-001",
                "ACC-002",
                new BigDecimal("300.00"),
                "USD",
                "2024-01-15T10:30:00Z");

        TransactionLedger stubLedger = TransactionLedger.create(
                "txn-listener-001", "ACC-001", "ACC-002",
                new BigDecimal("300.00"), "USD", "PENDING");

        when(ledgerService.persistLedgerRecord(argThat(e ->
                "txn-listener-001".equals(e.transactionId()))))
                .thenReturn(stubLedger);

        String payload = objectMapper.writeValueAsString(event);
        kafkaTemplate.send("payment-approved", payload);

        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() ->
                        verify(ledgerService).persistLedgerRecord(
                                argThat(e -> "txn-listener-001".equals(e.transactionId()))));
    }

    @Test
    void onPaymentApproved_whenDuplicate_stillSendsMqMessage() throws Exception {
        PaymentApprovedEvent event = new PaymentApprovedEvent(
                "txn-listener-dup-001",
                "ACC-001",
                "ACC-002",
                new BigDecimal("300.00"),
                "USD",
                "2024-01-15T10:30:00Z");

        // ledgerService returns an existing ledger (simulates prior successful DB write)
        TransactionLedger existingLedger = TransactionLedger.create(
                "txn-listener-dup-001", "ACC-001", "ACC-002",
                new BigDecimal("300.00"), "USD", "PENDING");

        when(ledgerService.persistLedgerRecord(argThat(e ->
                "txn-listener-dup-001".equals(e.transactionId()))))
                .thenReturn(existingLedger);

        String payload = objectMapper.writeValueAsString(event);
        kafkaTemplate.send("payment-approved", payload);

        // even when ledgerService returns an existing (duplicate) record,
        // the MQ send must still be attempted
        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() ->
                        verify(mqPublishingService).publishToClearingQueue(existingLedger));
    }
}
