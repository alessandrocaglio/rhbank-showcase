package com.showcase.clearing.messaging;

import com.showcase.clearing.dto.ClearingResult;

public interface PaymentCompletedPublisher {
    void publish(ClearingResult result);
}
