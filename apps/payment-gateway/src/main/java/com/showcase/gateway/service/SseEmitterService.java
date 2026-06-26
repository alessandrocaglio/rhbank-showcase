package com.showcase.gateway.service;

import com.showcase.gateway.dto.PaymentStatusEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface SseEmitterService {

    SseEmitter register(String transactionId);

    void resolve(String transactionId, PaymentStatusEvent event);
}
