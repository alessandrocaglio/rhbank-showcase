package com.showcase.gateway.service;

import com.showcase.gateway.dto.PaymentStatusEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges the Kafka consumer (resolve) with the SSE client (register) via
 * CompletableFuture. Whichever side arrives first, the other side will still
 * receive the event — no race condition between a fast pipeline and a
 * slow-connecting browser client.
 */
@Service
public class SseEmitterServiceImpl implements SseEmitterService {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterServiceImpl.class);

    private final ConcurrentHashMap<String, CompletableFuture<PaymentStatusEvent>> pending =
            new ConcurrentHashMap<>();

    private CompletableFuture<PaymentStatusEvent> getOrCreate(String txId) {
        return pending.computeIfAbsent(txId, k -> new CompletableFuture<>());
    }

    @Override
    public SseEmitter register(String transactionId) {
        SseEmitter emitter = new SseEmitter(300_000L);
        CompletableFuture<PaymentStatusEvent> future = getOrCreate(transactionId);

        // whenComplete fires immediately if the future is already done (pipeline was fast)
        // or later when resolve() completes the future (client connected first)
        future.whenComplete((event, ex) -> {
            pending.remove(transactionId);
            if (ex != null) {
                emitter.completeWithError(ex);
                return;
            }
            try {
                emitter.send(event);
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        });

        emitter.onTimeout(() -> {
            log.warn("SSE emitter timed out for transactionId={}", transactionId);
            pending.computeIfPresent(transactionId, (k, f) -> {
                f.cancel(true);
                return null;
            });
        });
        emitter.onError(ex -> pending.remove(transactionId));

        return emitter;
    }

    @Override
    public void resolve(String transactionId, PaymentStatusEvent event) {
        boolean completed = getOrCreate(transactionId).complete(event);
        if (!completed) {
            log.warn("Duplicate resolve ignored for transactionId={}", transactionId);
        }
    }
}
