package com.showcase.verifier.grpc;

import com.showcase.grpc.account.VerifyAccountRequest;
import com.showcase.grpc.account.VerifyAccountResponse;
import com.showcase.verifier.dto.VerificationResult;
import com.showcase.verifier.service.AccountVerificationService;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceGrpcImplTest {

    @Mock
    AccountVerificationService verificationService;

    @Mock
    StreamObserver<VerifyAccountResponse> responseObserver;

    @InjectMocks
    AccountServiceGrpcImpl impl;

    private VerifyAccountRequest buildRequest(String txId, String source, String destination,
                                              double amount, String currency) {
        return VerifyAccountRequest.newBuilder()
                .setTransactionId(txId)
                .setSourceAccount(source)
                .setDestinationAccount(destination)
                .setAmount(amount)
                .setCurrency(currency)
                .build();
    }

    @Test
    void shouldReturnApprovedResponse() {
        when(verificationService.verify(
                eq("TXN-001"), eq("ACC-001"), eq("ACC-002"),
                any(BigDecimal.class), eq("USD")))
                .thenReturn(VerificationResult.ofApproved());

        impl.verifyAccount(buildRequest("TXN-001", "ACC-001", "ACC-002", 100.0, "USD"),
                responseObserver);

        ArgumentCaptor<VerifyAccountResponse> captor = ArgumentCaptor.forClass(VerifyAccountResponse.class);
        verify(responseObserver).onNext(captor.capture());
        verify(responseObserver).onCompleted();

        VerifyAccountResponse response = captor.getValue();
        assertTrue(response.getApproved());
        assertEquals("", response.getReason());
    }

    @Test
    void shouldReturnRejectedResponse() {
        when(verificationService.verify(
                eq("TXN-002"), eq("ACC-003"), eq("ACC-001"),
                any(BigDecimal.class), eq("EUR")))
                .thenReturn(VerificationResult.ofRejected("Insufficient balance"));

        impl.verifyAccount(buildRequest("TXN-002", "ACC-003", "ACC-001", 500.0, "EUR"),
                responseObserver);

        ArgumentCaptor<VerifyAccountResponse> captor = ArgumentCaptor.forClass(VerifyAccountResponse.class);
        verify(responseObserver).onNext(captor.capture());
        verify(responseObserver).onCompleted();

        VerifyAccountResponse response = captor.getValue();
        assertFalse(response.getApproved());
        assertEquals("Insufficient balance", response.getReason());
    }

    @Test
    void shouldMapServiceExceptionToGrpcInternalError() {
        when(verificationService.verify(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Unexpected DB error"));

        impl.verifyAccount(buildRequest("TXN-003", "ACC-001", "ACC-002", 10.0, "USD"),
                responseObserver);

        verify(responseObserver).onError(any());
    }
}
