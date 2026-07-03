package com.showcase.verifier.outbox;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Message;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class OutboxPoller {

    private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier.get(key);
        }
    };

    private final OutboxRepository outboxRepository;
    private final MutinyEmitter<String> emitter;

    @Inject
    public OutboxPoller(OutboxRepository outboxRepository,
                        @Channel("payment-approved") MutinyEmitter<String> emitter) {
        this.outboxRepository = outboxRepository;
        this.emitter = emitter;
    }

    @Scheduled(every = "2s", delayed = "5s")
    @Transactional
    void poll() {
        List<OutboxMessage> unsent = outboxRepository.findUnsent();
        for (OutboxMessage msg : unsent) {
            try {
                if (msg.traceparent != null) {
                    publishWithContext(msg);
                } else {
                    emitter.sendAndAwait(msg.payload);
                }
                msg.sent   = true;
                msg.sentAt = Instant.now();
            } catch (Exception e) {
                Log.errorf(e, "Failed to publish outbox message id=%d, will retry", msg.id);
            }
        }
    }

    private void publishWithContext(OutboxMessage msg) {
        Map<String, String> carrier = msg.tracestate != null
            ? Map.of("traceparent", msg.traceparent, "tracestate", msg.tracestate)
            : Map.of("traceparent", msg.traceparent);

        Context parentCtx = GlobalOpenTelemetry.getPropagators()
            .getTextMapPropagator()
            .extract(Context.current(), carrier, MAP_GETTER);

        Tracer tracer = GlobalOpenTelemetry.getTracer("account-verifier");
        var spanBuilder = tracer.spanBuilder("outbox.kafka.publish")
            .setParent(parentCtx)
            .setAttribute("outbox.topic", msg.topic);
        if (msg.id != null) {
            spanBuilder.setAttribute("outbox.message.id", msg.id);
        }
        Span publishSpan = spanBuilder.startSpan();

        try (Scope scope = publishSpan.makeCurrent()) {
            RecordHeaders headers = new RecordHeaders();
            headers.add("traceparent", msg.traceparent.getBytes(StandardCharsets.UTF_8));
            if (msg.tracestate != null && !msg.tracestate.isBlank()) {
                headers.add("tracestate", msg.tracestate.getBytes(StandardCharsets.UTF_8));
            }
            OutgoingKafkaRecordMetadata<Void> meta = OutgoingKafkaRecordMetadata.<Void>builder()
                .withHeaders(headers)
                .build();

            emitter.sendMessageAndAwait(Message.of(msg.payload).addMetadata(meta));
        } catch (Exception e) {
            publishSpan.setStatus(StatusCode.ERROR, e.getMessage());
            publishSpan.recordException(e);
            throw e;
        } finally {
            publishSpan.end();
        }
    }
}
