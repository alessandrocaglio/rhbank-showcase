package com.showcase.engine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.showcase.engine.domain.TransactionLedger;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;

import java.math.BigDecimal;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MqPublishingServiceImplTest {

    private static final String TEST_QUEUE = "DEV.QUEUE.CLEARING";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JmsTemplate jmsTemplate = mock(JmsTemplate.class);
    private final MqPublishingServiceImpl service =
            new MqPublishingServiceImpl(jmsTemplate, objectMapper, TEST_QUEUE);

    private TransactionLedger buildTestLedger() {
        return TransactionLedger.create(
                "txn-001",
                "ACC-001",
                "ACC-002",
                new BigDecimal("150.00"),
                "USD",
                "PENDING");
    }

    @Test
    void shouldSendToCorrectQueueWithExpectedJmsProperties() throws Exception {
        TransactionLedger ledger = buildTestLedger();

        Session mockSession = mock(Session.class);
        TextMessage mockTextMessage = mock(TextMessage.class);
        when(mockSession.createTextMessage(anyString())).thenReturn(mockTextMessage);

        ArgumentCaptor<MessageCreator> creatorCaptor = ArgumentCaptor.forClass(MessageCreator.class);

        service.publishToClearingQueue(ledger);

        verify(jmsTemplate).send(eq(TEST_QUEUE), creatorCaptor.capture());

        // Execute the captured MessageCreator to verify JMS message construction
        creatorCaptor.getValue().createMessage(mockSession);

        verify(mockTextMessage).setStringProperty(eq("transactionId"), eq("txn-001"));
        verify(mockTextMessage).setStringProperty(eq("traceparent"), notNull());
    }

    @Test
    void shouldSerializePayloadContainingTransactionId() throws Exception {
        TransactionLedger ledger = buildTestLedger();

        Session mockSession = mock(Session.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        TextMessage mockTextMessage = mock(TextMessage.class);
        when(mockSession.createTextMessage(jsonCaptor.capture())).thenReturn(mockTextMessage);

        ArgumentCaptor<MessageCreator> creatorCaptor = ArgumentCaptor.forClass(MessageCreator.class);

        service.publishToClearingQueue(ledger);

        verify(jmsTemplate).send(eq(TEST_QUEUE), creatorCaptor.capture());

        creatorCaptor.getValue().createMessage(mockSession);

        String json = jsonCaptor.getValue();
        assertThat(json).contains("txn-001");
        assertThat(json).contains("ACC-001");
        assertThat(json).contains("ACC-002");
    }

    @Test
    void recoverMethod_existsWithCorrectSignature() throws NoSuchMethodException {
        Method method = MqPublishingServiceImpl.class.getDeclaredMethod(
            "recoverPublishToClearingQueue", Exception.class, TransactionLedger.class);
        assertNotNull(method);
    }
}
