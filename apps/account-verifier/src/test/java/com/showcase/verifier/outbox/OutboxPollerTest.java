package com.showcase.verifier.outbox;

import io.smallrye.reactive.messaging.MutinyEmitter;
import org.apache.kafka.common.header.Header;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
        OutboxMessage msg1 = OutboxMessage.of("payment-approved", "{\"transactionId\":\"txn-1\"}", null, null);
        OutboxMessage msg2 = OutboxMessage.of("payment-approved", "{\"transactionId\":\"txn-2\"}", null, null);
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
        OutboxMessage msg = OutboxMessage.of("payment-approved", "{\"transactionId\":\"txn-3\"}", null, null);
        when(outboxRepository.findUnsent()).thenReturn(List.of(msg));
        doThrow(new RuntimeException("Kafka unavailable")).when(emitter).sendAndAwait(anyString());

        poller.poll();

        assertFalse(msg.sent, "msg should remain unsent after emitter failure");
    }

    @Test
    void poll_continuesPublishingRemainingMessagesAfterOneFailure() {
        OutboxMessage failMsg = OutboxMessage.of("payment-approved", "{\"transactionId\":\"txn-fail\"}", null, null);
        OutboxMessage okMsg = OutboxMessage.of("payment-approved", "{\"transactionId\":\"txn-ok\"}", null, null);
        when(outboxRepository.findUnsent()).thenReturn(List.of(failMsg, okMsg));
        doThrow(new RuntimeException("Kafka unavailable"))
                .when(emitter).sendAndAwait("{\"transactionId\":\"txn-fail\"}");

        poller.poll();

        assertFalse(failMsg.sent, "failMsg should remain unsent");
        assertTrue(okMsg.sent, "okMsg should be marked sent after the failed message");
    }

    @Test
    void poll_nullTraceparent_usesPlainSendAndAwait() {
        OutboxMessage msg = OutboxMessage.of("payment-approved", "{\"transactionId\":\"txn-no-ctx\"}", null, null);
        when(outboxRepository.findUnsent()).thenReturn(List.of(msg));

        poller.poll();

        verify(emitter).sendAndAwait("{\"transactionId\":\"txn-no-ctx\"}");
        verify(emitter, never()).sendMessageAndAwait(any(Message.class));
        assertTrue(msg.sent);
        assertNotNull(msg.sentAt);
    }

    @Test
    @SuppressWarnings("unchecked")
    void poll_nonNullTraceparent_sendMessageWithKafkaHeader() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        OutboxMessage msg = OutboxMessage.of("payment-approved", "{\"transactionId\":\"txn-ctx\"}", traceparent, null);
        when(outboxRepository.findUnsent()).thenReturn(List.of(msg));

        poller.poll();

        verify(emitter, never()).sendAndAwait(anyString());
        ArgumentCaptor<Message<String>> captor = ArgumentCaptor.forClass(Message.class);
        verify(emitter).sendMessageAndAwait(captor.capture());

        Message<String> captured = captor.getValue();
        io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata<?> meta =
            captured.getMetadata(io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata.class)
                .orElseThrow(() -> new AssertionError("Expected OutgoingKafkaRecordMetadata"));

        Header header = meta.getHeaders().lastHeader("traceparent");
        assertNotNull(header, "Expected traceparent header in Kafka metadata");
        assertArrayEquals(traceparent.getBytes(StandardCharsets.UTF_8), header.value(),
            "traceparent header value must match the stored value");

        assertTrue(msg.sent);
        assertNotNull(msg.sentAt);
    }

    @Test
    @SuppressWarnings("unchecked")
    void poll_nonNullTraceparentWithTracestate_sendsBothHeaders() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        String tracestate  = "congo=t61rcWkgMzE";
        OutboxMessage msg = OutboxMessage.of("payment-approved", "{\"transactionId\":\"txn-ts\"}", traceparent, tracestate);
        when(outboxRepository.findUnsent()).thenReturn(List.of(msg));

        poller.poll();

        ArgumentCaptor<Message<String>> captor = ArgumentCaptor.forClass(Message.class);
        verify(emitter).sendMessageAndAwait(captor.capture());

        io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata<?> meta =
            captor.getValue()
                .getMetadata(io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata.class)
                .orElseThrow(() -> new AssertionError("Expected OutgoingKafkaRecordMetadata"));

        Header tpHeader = meta.getHeaders().lastHeader("traceparent");
        Header tsHeader = meta.getHeaders().lastHeader("tracestate");
        assertNotNull(tpHeader, "Expected traceparent header");
        assertNotNull(tsHeader, "Expected tracestate header");
        assertArrayEquals(traceparent.getBytes(StandardCharsets.UTF_8), tpHeader.value());
        assertArrayEquals(tracestate.getBytes(StandardCharsets.UTF_8), tsHeader.value());
    }

    @Test
    @SuppressWarnings("unchecked")
    void poll_nonNullTraceparent_leavesUnsentWhenEmitterThrows() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        OutboxMessage msg = OutboxMessage.of("payment-approved", "{\"transactionId\":\"txn-err\"}", traceparent, null);
        when(outboxRepository.findUnsent()).thenReturn(List.of(msg));
        doThrow(new RuntimeException("Kafka unavailable")).when(emitter).sendMessageAndAwait(any(Message.class));

        poller.poll();

        assertFalse(msg.sent, "msg should remain unsent when sendMessageAndAwait throws");
    }
}
