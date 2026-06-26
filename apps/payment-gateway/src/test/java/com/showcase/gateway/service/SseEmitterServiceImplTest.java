package com.showcase.gateway.service;

import com.showcase.gateway.dto.PaymentStatusEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

@ExtendWith(MockitoExtension.class)
class SseEmitterServiceImplTest {

    @InjectMocks
    private SseEmitterServiceImpl sseEmitterService;

    // (a) register(txId) returns non-null SseEmitter
    @Test
    void register_returnsNonNullEmitter() {
        SseEmitter emitter = sseEmitterService.register("tx-001");
        assertThat(emitter).isNotNull();
    }

    // (b) register same txId twice → second replaces the first (map size stays 1)
    @Test
    void register_sameTxIdTwice_replacesOldEmitter() throws Exception {
        sseEmitterService.register("tx-dup");
        sseEmitterService.register("tx-dup");

        ConcurrentHashMap<String, SseEmitter> store = getStore();
        assertThat(store).containsKey("tx-dup");
        assertThat(store.size()).isEqualTo(1);
    }

    // (c) resolve(txId, event) → no exception thrown; map size = 0 after resolve
    @Test
    void resolve_sendsEventAndRemovesFromMap() throws Exception {
        sseEmitterService.register("tx-resolve");

        PaymentStatusEvent event = new PaymentStatusEvent("tx-resolve", "COMPLETED", "2024-01-15T10:30:00Z", "Done");

        assertThatNoException().isThrownBy(() -> sseEmitterService.resolve("tx-resolve", event));

        ConcurrentHashMap<String, SseEmitter> store = getStore();
        assertThat(store).doesNotContainKey("tx-resolve");
    }

    // (d) resolve on unknown txId → no exception (silent no-op)
    @Test
    void resolve_unknownTxId_noException() {
        PaymentStatusEvent event = new PaymentStatusEvent("unknown", "COMPLETED", "2024-01-15T10:30:00Z", "Done");
        assertThatNoException().isThrownBy(() -> sseEmitterService.resolve("unknown", event));
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, SseEmitter> getStore() throws Exception {
        Field field = SseEmitterServiceImpl.class.getDeclaredField("store");
        field.setAccessible(true);
        return (ConcurrentHashMap<String, SseEmitter>) field.get(sseEmitterService);
    }
}
