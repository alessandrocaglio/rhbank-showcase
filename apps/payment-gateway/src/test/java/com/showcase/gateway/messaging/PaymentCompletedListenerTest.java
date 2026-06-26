package com.showcase.gateway.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.showcase.gateway.client.AccountVerifierClient;
import com.showcase.gateway.dto.PaymentCompletedEvent;
import com.showcase.gateway.dto.PaymentStatusEvent;
import com.showcase.gateway.service.SseEmitterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"payment-completed", "payment-completed.DLT"})
@DirtiesContext
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=",
        "grpc.client.account-verifier.address=static://localhost:19999",
        "grpc.client.account-verifier.negotiation-type=plaintext"
})
class PaymentCompletedListenerTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        public JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "RS256")
                    .claim("sub", "test-user")
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();
        }
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockBean
    private SseEmitterService sseEmitterService;

    @MockBean
    private AccountVerifierClient accountVerifierClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void onPaymentCompleted_resolvesCorrectEmitter() throws Exception {
        String txId = "txn-test-001";
        var event = new PaymentCompletedEvent(txId, "COMPLETED",
                "2024-01-15T10:30:00Z", "Clearing successful");
        String json = objectMapper.writeValueAsString(event);

        kafkaTemplate.send("payment-completed", json);

        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() ->
                       verify(sseEmitterService).resolve(eq(txId), any(PaymentStatusEvent.class)));
    }
}
