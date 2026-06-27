package com.showcase.verifier.outbox;

import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.reactive.messaging.MutinyEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Channel;

import java.time.Instant;
import java.util.List;

/**
 * Polls the outbox_messages table on a fixed schedule and publishes unsent messages to Kafka.
 *
 * <p>The {@code @Transactional} annotation ensures that reading unsent rows and marking them
 * as sent happen within a single JTA transaction, so a crash between the two steps leaves the
 * message in the unsent state and it will be retried on the next poll cycle.
 */
@ApplicationScoped
public class OutboxPoller {

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
                emitter.sendAndAwait(msg.payload);
                msg.sent = true;
                msg.sentAt = Instant.now();
            } catch (Exception e) {
                // Leave sent=false so the message is retried on the next poll cycle.
                Log.errorf(e, "Failed to publish outbox message id=%d, will retry", msg.id);
            }
        }
    }
}
