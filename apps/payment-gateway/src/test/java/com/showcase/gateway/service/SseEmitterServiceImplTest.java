package com.showcase.gateway.service;

import com.showcase.gateway.dto.PaymentStatusEvent;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class SseEmitterServiceImplTest {

    private final SseEmitterServiceImpl sse = new SseEmitterServiceImpl();
    private final PaymentStatusEvent event =
            new PaymentStatusEvent("tx-1", "COMPLETED", "2024-01-15T10:30:00Z", "Done");

    // register() returns a non-null SseEmitter
    @Test
    void register_returnsNonNullEmitter() {
        assertThat(sse.register("tx-001")).isNotNull();
    }

    // Normal order: register then resolve — no exception thrown
    @Test
    void resolve_afterRegister_noException() {
        sse.register("tx-resolve");
        assertThatNoException().isThrownBy(() -> sse.resolve("tx-resolve", event));
    }

    // Race-condition order: resolve fires before any client connects — no exception
    @Test
    void resolve_beforeRegister_noException() {
        assertThatNoException().isThrownBy(() -> sse.resolve("tx-late", event));
    }

    // Late-connecting client: resolve first, then register — emitter is returned (event delivery
    // happens asynchronously via whenComplete, verified by no exception)
    @Test
    void register_afterResolve_returnsEmitter() {
        sse.resolve("tx-buffered", event);
        SseEmitter emitter = sse.register("tx-buffered");
        assertThat(emitter).isNotNull();
    }

    // Duplicate resolve is silently ignored
    @Test
    void resolve_calledTwice_noException() {
        sse.register("tx-dup");
        sse.resolve("tx-dup", event);
        assertThatNoException().isThrownBy(() -> sse.resolve("tx-dup", event));
    }
}
