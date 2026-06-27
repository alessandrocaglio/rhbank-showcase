package com.showcase.verifier.outbox;

import io.smallrye.reactive.messaging.MutinyEmitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPollerTest {

    @Mock
    OutboxRepository outboxRepository;

    @Mock
    MutinyEmitter<String> emitter;

    OutboxPoller poller;

    @BeforeEach
    void setUp() {
        poller = new OutboxPoller(outboxRepository, emitter);
    }

    @Test
    void poll_sendsEachUnsentMessageAndMarksItSent() {
        OutboxMessage msg1 = OutboxMessage.of("payment-approved", "{\"transactionId\":\"txn-1\"}");
        OutboxMessage msg2 = OutboxMessage.of("payment-approved", "{\"transactionId\":\"txn-2\"}");
        when(outboxRepository.findUnsent()).thenReturn(List.of(msg1, msg2));

        poller.poll();

        verify(emitter).sendAndAwait("{\"transactionId\":\"txn-1\"}");
        verify(emitter).sendAndAwait("{\"transactionId\":\"txn-2\"}");
        assertTrue(msg1.sent, "msg1 should be marked sent");
        assertNotNull(msg1.sentAt, "msg1 sentAt should be set");
        assertTrue(msg2.sent, "msg2 should be marked sent");
        assertNotNull(msg2.sentAt, "msg2 sentAt should be set");
    }

    @Test
    void poll_doesNothingWhenNoUnsentMessages() {
        when(outboxRepository.findUnsent()).thenReturn(List.of());

        poller.poll();

        verify(emitter, never()).sendAndAwait(anyString());
    }

    @Test
    void poll_leavesMessageUnsentWhenEmitterThrows() {
        OutboxMessage msg = OutboxMessage.of("payment-approved", "{\"transactionId\":\"txn-3\"}");
        when(outboxRepository.findUnsent()).thenReturn(List.of(msg));
        doThrow(new RuntimeException("Kafka unavailable")).when(emitter).sendAndAwait(anyString());

        poller.poll();

        assertFalse(msg.sent, "msg should remain unsent after emitter failure");
    }

    @Test
    void poll_continuesPublishingRemainingMessagesAfterOneFailure() {
        OutboxMessage failMsg = OutboxMessage.of("payment-approved", "{\"transactionId\":\"txn-fail\"}");
        OutboxMessage okMsg = OutboxMessage.of("payment-approved", "{\"transactionId\":\"txn-ok\"}");
        when(outboxRepository.findUnsent()).thenReturn(List.of(failMsg, okMsg));
        doThrow(new RuntimeException("Kafka unavailable"))
                .when(emitter).sendAndAwait("{\"transactionId\":\"txn-fail\"}");

        poller.poll();

        assertFalse(failMsg.sent, "failMsg should remain unsent");
        assertTrue(okMsg.sent, "okMsg should be marked sent after the failed message");
    }
}
