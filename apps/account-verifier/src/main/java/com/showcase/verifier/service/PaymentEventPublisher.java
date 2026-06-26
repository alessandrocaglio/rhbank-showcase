package com.showcase.verifier.service;

import com.showcase.verifier.dto.PaymentApprovedEvent;

/**
 * Port for publishing payment events to downstream channels.
 * Full implementation (Kafka via SmallRye) is wired in T06.
 */
public interface PaymentEventPublisher {

    void publishApproved(PaymentApprovedEvent event);
}
