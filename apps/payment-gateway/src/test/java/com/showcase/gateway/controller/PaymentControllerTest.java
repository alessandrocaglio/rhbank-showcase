package com.showcase.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.showcase.gateway.config.GlobalExceptionHandler;
import com.showcase.gateway.config.WebConfig;
import com.showcase.gateway.dto.PaymentRequest;
import com.showcase.gateway.dto.PaymentResponse;
import com.showcase.gateway.exception.AccountVerificationException;
import com.showcase.gateway.service.PaymentService;
import com.showcase.gateway.service.SseEmitterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PaymentController.class)
@Import({WebConfig.class, GlobalExceptionHandler.class})
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PaymentService paymentService;

    @MockBean
    private SseEmitterService sseEmitterService;

    // (a) Valid body → 202
    @Test
    void postPayments_withValidBody_returns202() throws Exception {
        PaymentResponse response = new PaymentResponse("txn-001", "PENDING", "Accepted");
        when(paymentService.initiatePayment(any())).thenReturn(response);

        PaymentRequest request = new PaymentRequest("ACC-001", "ACC-002", new BigDecimal("150.00"), "USD");

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.transactionId").value("txn-001"));
    }

    // (b) POST with missing sourceAccount → 400
    @Test
    void postPayments_withMissingSourceAccount_returns400() throws Exception {
        String invalidJson = """
                {
                    "destinationAccount": "ACC-002",
                    "amount": 150.00,
                    "currency": "USD"
                }
                """;

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // (c) POST with negative amount → 400
    @Test
    void postPayments_withNegativeAmount_returns400() throws Exception {
        String invalidJson = """
                {
                    "sourceAccount": "ACC-001",
                    "destinationAccount": "ACC-002",
                    "amount": -50.00,
                    "currency": "USD"
                }
                """;

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // (d) POST with PaymentService throwing AccountVerificationException → 422
    @Test
    void postPayments_whenAccountVerificationFails_returns422() throws Exception {
        when(paymentService.initiatePayment(any()))
                .thenThrow(new AccountVerificationException("Insufficient balance"));

        PaymentRequest request = new PaymentRequest("ACC-001", "ACC-002", new BigDecimal("150.00"), "USD");

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VERIFICATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Insufficient balance"));
    }

    // (e) GET /api/v1/payments/stream/test-tx → 200 (SseEmitter returned)
    @Test
    void getPaymentStream_returnsOkWithSseEmitter() throws Exception {
        when(sseEmitterService.register(eq("test-tx"))).thenReturn(new SseEmitter(300_000L));

        mockMvc.perform(get("/api/v1/payments/stream/test-tx"))
                .andExpect(status().isOk());
    }
}
