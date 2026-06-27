package com.showcase.verifier.outbox;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class OutboxRepository implements PanacheRepository<OutboxMessage> {

    /**
     * Returns all outbox messages that have not yet been published, ordered by insertion order.
     */
    public List<OutboxMessage> findUnsent() {
        return find("sent = false ORDER BY id").list();
    }
}
