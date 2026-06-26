package com.showcase.gateway.controller;

import com.showcase.gateway.dto.PaymentRequest;
import com.showcase.gateway.dto.PaymentResponse;
import com.showcase.gateway.service.PaymentService;
import com.showcase.gateway.service.SseEmitterService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final SseEmitterService sseEmitterService;

    public PaymentController(PaymentService paymentService, SseEmitterService sseEmitterService) {
        this.paymentService = paymentService;
        this.sseEmitterService = sseEmitterService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> initiatePayment(@Valid @RequestBody PaymentRequest request) {
        PaymentResponse response = paymentService.initiatePayment(request);
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping(value = "/stream/{transactionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamPaymentStatus(@PathVariable String transactionId) {
        SseEmitter emitter = sseEmitterService.register(transactionId);
        return ResponseEntity.ok()
                .header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
                .body(emitter);
    }
}
