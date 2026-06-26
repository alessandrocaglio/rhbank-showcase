package com.showcase.clearing.messaging;

import com.ibm.mq.jms.MQConnectionFactory;
import com.showcase.clearing.config.MqProperties;
import com.showcase.clearing.dto.ClearingResult;
import com.showcase.clearing.service.ClearingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.jms.JMSException;
import javax.jms.TextMessage;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MqMessageListenerTest {

    @Mock
    MQConnectionFactory connectionFactory;

    @Mock
    ClearingService clearingService;

    @Mock
    PaymentCompletedPublisher publisher;

    @Mock
    MqProperties mqProperties;

    @Mock
    TextMessage message;

    MqMessageListener listener;

    @BeforeEach
    void setUp() {
        when(mqProperties.getQueueName()).thenReturn("DEV.QUEUE.CLEARING");
        listener = new MqMessageListener(connectionFactory, clearingService, publisher, mqProperties);
    }

    @Test
    void processMessage_delegatesToClearingServiceAndPublisher() throws JMSException {
        when(message.getStringProperty("traceparent")).thenReturn("00-abc123def456abc123def456abc12345-abc123def456abcd-01");
        when(message.getStringProperty("transactionId")).thenReturn("txn-001");
        when(message.getText()).thenReturn("{}");
        when(clearingService.process("txn-001")).thenReturn(
                new ClearingResult("txn-001", "COMPLETED", "OK", "2024-01-01T00:00:00Z"));

        listener.processMessage(message);

        verify(clearingService).process("txn-001");
        verify(publisher).publish(any(ClearingResult.class));
    }

    @Test
    void processMessage_handlesNullTraceparent() throws JMSException {
        when(message.getStringProperty("traceparent")).thenReturn(null);
        when(message.getStringProperty("transactionId")).thenReturn("txn-002");
        when(message.getText()).thenReturn("{}");
        when(clearingService.process("txn-002")).thenReturn(
                new ClearingResult("txn-002", "FAILED", "Rejected", "2024-01-01T00:00:00Z"));

        listener.processMessage(message);

        verify(clearingService).process("txn-002");
        verify(publisher).publish(any(ClearingResult.class));
    }

    @Test
    void processMessage_publishesResultFromClearingService() throws JMSException {
        ClearingResult expected = new ClearingResult("txn-003", "COMPLETED", "OK", "2024-01-01T00:00:00Z");
        when(message.getStringProperty("traceparent")).thenReturn(null);
        when(message.getStringProperty("transactionId")).thenReturn("txn-003");
        when(message.getText()).thenReturn("{}");
        when(clearingService.process("txn-003")).thenReturn(expected);

        listener.processMessage(message);

        verify(publisher).publish(expected);
    }

    @Test
    void processMessage_recordsSpanOnClearingServiceException() throws JMSException {
        when(message.getStringProperty("traceparent")).thenReturn(null);
        when(message.getStringProperty("transactionId")).thenReturn("txn-004");
        when(message.getText()).thenReturn("{}");
        when(clearingService.process("txn-004")).thenThrow(new RuntimeException("Unexpected failure"));

        assertThrows(RuntimeException.class, () -> listener.processMessage(message));

        verify(clearingService).process("txn-004");
        verifyNoInteractions(publisher);
    }

}
