package com.showcase.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.showcase.gateway.config.GlobalExceptionHandler;
import com.showcase.gateway.config.SecurityConfig;
import com.showcase.gateway.dto.PaymentRequest;
import com.showcase.gateway.dto.PaymentResponse;
import com.showcase.gateway.exception.AccountVerificationException;
import com.showcase.gateway.service.PaymentService;
import com.showcase.gateway.service.SseEmitterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PaymentController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, PaymentControllerTest.MockJwtDecoderConfig.class})
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PaymentService paymentService;

    @MockBean
    private SseEmitterService sseEmitterService;

    @TestConfiguration
    static class MockJwtDecoderConfig {

        @Bean
        public JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "RS256")
                    .claim("sub", "test-user")
                    .claim("realm_access", Map.of("roles", List.of("payment-init")))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();
        }
    }

    // (a) Valid JWT with payment-init role + valid body → 202
    @Test
    void postPayments_withValidJwtAndValidBody_returns202() throws Exception {
        PaymentResponse response = new PaymentResponse("txn-001", "PENDING", "Accepted");
        when(paymentService.initiatePayment(any())).thenReturn(response);

        PaymentRequest request = new PaymentRequest("ACC-001", "ACC-002", new BigDecimal("150.00"), "USD");

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_payment-init"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.transactionId").value("txn-001"));
    }

    // (b) POST without auth → 401
    @Test
    void postPayments_withoutAuth_returns401() throws Exception {
        PaymentRequest request = new PaymentRequest("ACC-001", "ACC-002", new BigDecimal("150.00"), "USD");

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    // (c) POST with valid JWT but missing sourceAccount → 400
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
                        .content(invalidJson)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_payment-init"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // (d) POST with valid JWT but negative amount → 400
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
                        .content(invalidJson)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_payment-init"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // (e) POST with valid JWT, PaymentService throws AccountVerificationException → 422
    @Test
    void postPayments_whenAccountVerificationFails_returns422() throws Exception {
        when(paymentService.initiatePayment(any()))
                .thenThrow(new AccountVerificationException("Insufficient balance"));

        PaymentRequest request = new PaymentRequest("ACC-001", "ACC-002", new BigDecimal("150.00"), "USD");

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_payment-init"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VERIFICATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Insufficient balance"));
    }

    // (f) GET /api/v1/payments/stream/test-tx → 200 (SseEmitter returned)
    @Test
    void getPaymentStream_returnsOkWithSseEmitter() throws Exception {
        when(sseEmitterService.register(eq("test-tx"))).thenReturn(new SseEmitter(300_000L));

        mockMvc.perform(get("/api/v1/payments/stream/test-tx"))
                .andExpect(status().isOk());
    }
}
