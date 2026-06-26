package com.showcase.gateway.service;

import com.showcase.gateway.dto.PaymentStatusEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SseEmitterServiceImpl implements SseEmitterService {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterServiceImpl.class);

    private final ConcurrentHashMap<String, SseEmitter> store = new ConcurrentHashMap<>();

    @Override
    public SseEmitter register(String transactionId) {
        SseEmitter emitter = new SseEmitter(300_000L);
        emitter.onCompletion(() -> store.remove(transactionId));
        emitter.onTimeout(() -> store.remove(transactionId));
        emitter.onError(ex -> store.remove(transactionId));
        store.put(transactionId, emitter);
        return emitter;
    }

    @Override
    public void resolve(String transactionId, PaymentStatusEvent event) {
        SseEmitter emitter = store.get(transactionId);
        if (emitter == null) {
            log.warn("No SSE emitter found for transactionId={}", transactionId);
            return;
        }
        try {
            emitter.send(event);
            emitter.complete();
        } catch (IOException ex) {
            log.warn("Failed to send SSE event for transactionId={}: {}", transactionId, ex.getMessage());
            emitter.completeWithError(ex);
        } finally {
            store.remove(transactionId);
        }
    }
}
