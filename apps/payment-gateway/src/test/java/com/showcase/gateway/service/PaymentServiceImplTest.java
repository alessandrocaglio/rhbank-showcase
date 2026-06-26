package com.showcase.gateway.service;

import com.showcase.gateway.client.AccountVerifierClient;
import com.showcase.gateway.dto.PaymentRequest;
import com.showcase.gateway.dto.PaymentResponse;
import com.showcase.gateway.exception.AccountVerificationException;
import com.showcase.grpc.account.VerifyAccountResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    @Mock
    AccountVerifierClient accountVerifierClient;

    PaymentServiceImpl service;

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    @BeforeEach
    void setUp() {
        service = new PaymentServiceImpl(accountVerifierClient);
    }

    // (a) gRPC returns approved=true → PaymentResponse returned with non-null transactionId, status="PENDING"
    @Test
    void initiatePayment_whenApproved_returnsPaymentResponseWithPendingStatus() {
        when(accountVerifierClient.verify(any())).thenReturn(
                VerifyAccountResponse.newBuilder().setApproved(true).build());

        PaymentRequest request = new PaymentRequest("ACC-001", "ACC-002", new BigDecimal("150.00"), "USD");

        PaymentResponse response = service.initiatePayment(request);

        assertThat(response.transactionId()).isNotNull();
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.message()).isEqualTo("Payment accepted for processing");
    }

    // (b) gRPC returns approved=false with reason → AccountVerificationException thrown with reason in message
    @Test
    void initiatePayment_whenNotApproved_throwsAccountVerificationExceptionWithReason() {
        when(accountVerifierClient.verify(any())).thenReturn(
                VerifyAccountResponse.newBuilder()
                        .setApproved(false)
                        .setReason("Insufficient balance")
                        .build());

        PaymentRequest request = new PaymentRequest("ACC-003", "ACC-002", new BigDecimal("9999.00"), "USD");

        assertThatThrownBy(() -> service.initiatePayment(request))
                .isInstanceOf(AccountVerificationException.class)
                .hasMessage("Insufficient balance");
    }

    // (c) accountVerifierClient.verify() throws AccountVerificationException → propagates unchanged
    @Test
    void initiatePayment_whenClientThrowsAccountVerificationException_propagatesUnchanged() {
        AccountVerificationException original =
                new AccountVerificationException("gRPC call to account-verifier failed: unavailable");

        when(accountVerifierClient.verify(any())).thenThrow(original);

        PaymentRequest request = new PaymentRequest("ACC-001", "ACC-002", new BigDecimal("100.00"), "USD");

        assertThatThrownBy(() -> service.initiatePayment(request))
                .isSameAs(original);
    }

    // (d) transactionId in response is a valid UUID
    @Test
    void initiatePayment_transactionIdIsValidUuid() {
        when(accountVerifierClient.verify(any())).thenReturn(
                VerifyAccountResponse.newBuilder().setApproved(true).build());

        PaymentRequest request = new PaymentRequest("ACC-001", "ACC-002", new BigDecimal("50.00"), "EUR");

        PaymentResponse response = service.initiatePayment(request);

        assertThat(response.transactionId()).matches(UUID_PATTERN);
    }
}
