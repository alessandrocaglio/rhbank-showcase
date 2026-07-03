package com.showcase.verifier.outbox;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "outbox_messages")
public class OutboxMessage extends PanacheEntity {

    @Column(name = "topic", nullable = false, length = 100)
    public String topic;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    public String payload;

    @Column(name = "created_at")
    public Instant createdAt;

    @Column(name = "sent", nullable = false)
    public boolean sent;

    @Column(name = "sent_at")
    public Instant sentAt;

    @Column(name = "traceparent", length = 55)
    public String traceparent;

    @Column(name = "tracestate", length = 512)
    public String tracestate;

    public static OutboxMessage of(String topic, String payload, String traceparent, String tracestate) {
        OutboxMessage msg = new OutboxMessage();
        msg.topic       = topic;
        msg.payload     = payload;
        msg.createdAt   = Instant.now();
        msg.sent        = false;
        msg.traceparent = traceparent;
        msg.tracestate  = tracestate;
        return msg;
    }
}
