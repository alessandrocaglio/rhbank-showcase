package com.showcase.gateway.client;

import com.showcase.gateway.exception.AccountVerificationException;
import com.showcase.grpc.account.AccountServiceGrpc;
import com.showcase.grpc.account.VerifyAccountRequest;
import com.showcase.grpc.account.VerifyAccountResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountVerifierClientTest {

    @Mock(answer = Answers.RETURNS_SELF)
    AccountServiceGrpc.AccountServiceBlockingStub stub;

    AccountVerifierClient client;

    @BeforeEach
    void setUp() {
        client = new AccountVerifierClient(stub);
        // Inject the timeout value into the @Value field
        ReflectionTestUtils.setField(client, "timeoutSeconds", 5);
    }

    // (a) stub returns approved=true → response returned as-is
    @Test
    void verify_whenApproved_returnsResponse() {
        VerifyAccountRequest request = VerifyAccountRequest.newBuilder()
                .setTransactionId("txn-001")
                .setSourceAccount("ACC-001")
                .setDestinationAccount("ACC-002")
                .setAmount("100.00")
                .setCurrency("USD")
                .build();

        VerifyAccountResponse expected = VerifyAccountResponse.newBuilder()
                .setApproved(true)
                .build();

        when(stub.verifyAccount(request)).thenReturn(expected);

        VerifyAccountResponse result = client.verify(request);

        assertThat(result.getApproved()).isTrue();
    }

    // (b) stub returns approved=false → response returned as-is (mapping to exception is caller's responsibility)
    @Test
    void verify_whenNotApproved_returnsResponseWithoutThrowing() {
        VerifyAccountRequest request = VerifyAccountRequest.newBuilder()
                .setTransactionId("txn-002")
                .setSourceAccount("ACC-003")
                .setDestinationAccount("ACC-002")
                .setAmount("500.00")
                .setCurrency("USD")
                .build();

        VerifyAccountResponse expected = VerifyAccountResponse.newBuilder()
                .setApproved(false)
                .setReason("Insufficient balance")
                .build();

        when(stub.verifyAccount(request)).thenReturn(expected);

        VerifyAccountResponse result = client.verify(request);

        assertThat(result.getApproved()).isFalse();
        assertThat(result.getReason()).isEqualTo("Insufficient balance");
    }

    // (c) stub throws StatusRuntimeException → AccountVerificationException thrown
    @Test
    void verify_whenStubThrowsStatusRuntimeException_throwsAccountVerificationException() {
        VerifyAccountRequest request = VerifyAccountRequest.newBuilder()
                .setTransactionId("txn-003")
                .setSourceAccount("ACC-001")
                .setDestinationAccount("ACC-002")
                .setAmount("100.00")
                .setCurrency("USD")
                .build();

        StatusRuntimeException grpcException = Status.UNAVAILABLE
                .withDescription("account-verifier unreachable")
                .asRuntimeException();

        when(stub.verifyAccount(request)).thenThrow(grpcException);

        assertThatThrownBy(() -> client.verify(request))
                .isInstanceOf(AccountVerificationException.class)
                .hasMessageContaining("account-verifier unreachable");
    }

    // (d) stub throws DEADLINE_EXCEEDED → AccountVerificationException with "account-verifier" in message
    @Test
    void verify_whenDeadlineExceeded_throwsDescriptiveException() {
        VerifyAccountRequest request = VerifyAccountRequest.newBuilder()
                .setTransactionId("TXN-DL")
                .setSourceAccount("ACC-001")
                .setDestinationAccount("ACC-002")
                .setAmount("100.00")
                .setCurrency("USD")
                .build();

        StatusRuntimeException deadlineException = Status.DEADLINE_EXCEEDED
                .withDescription("deadline exceeded after 5s")
                .asRuntimeException();

        when(stub.verifyAccount(request)).thenThrow(deadlineException);

        assertThatThrownBy(() -> client.verify(request))
                .isInstanceOf(AccountVerificationException.class)
                .hasMessageContaining("account-verifier");
    }
}
