package com.showcase.verifier.grpc;

import com.showcase.grpc.account.VerifyAccountRequest;
import com.showcase.grpc.account.VerifyAccountResponse;
import com.showcase.verifier.dto.VerificationResult;
import com.showcase.verifier.service.AccountVerificationService;
import io.grpc.stub.StreamObserver;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.concurrent.Callable;

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

    @Mock
    Vertx vertx;

    @InjectMocks
    AccountServiceGrpcImpl impl;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void configureVertx() {
        // Execute the callable synchronously so tests don't need async coordination.
        // Future.succeededFuture / failedFuture call onComplete handlers immediately
        // when the future is already resolved.
        when(vertx.executeBlocking(any(Callable.class))).thenAnswer(inv -> {
            Callable<Object> callable = inv.getArgument(0);
            try {
                return Future.succeededFuture(callable.call());
            } catch (Throwable t) {
                return Future.failedFuture(t);
            }
        });
    }

    private VerifyAccountRequest buildRequest(String txId, String source, String destination,
                                              String amount, String currency) {
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

        impl.verifyAccount(buildRequest("TXN-001", "ACC-001", "ACC-002", "100.00", "USD"),
                responseObserver);

        var captor = org.mockito.ArgumentCaptor.forClass(VerifyAccountResponse.class);
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

        impl.verifyAccount(buildRequest("TXN-002", "ACC-003", "ACC-001", "500.00", "EUR"),
                responseObserver);

        var captor = org.mockito.ArgumentCaptor.forClass(VerifyAccountResponse.class);
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

        impl.verifyAccount(buildRequest("TXN-003", "ACC-001", "ACC-002", "10.00", "USD"),
                responseObserver);

        var errorCaptor = org.mockito.ArgumentCaptor.forClass(Throwable.class);
        verify(responseObserver).onError(errorCaptor.capture());
        var sre = (io.grpc.StatusRuntimeException) errorCaptor.getValue();
        assertEquals(io.grpc.Status.Code.INTERNAL, sre.getStatus().getCode());
        assertEquals("Account verification failed. See server logs.", sre.getStatus().getDescription());
        assertFalse(sre.getStatus().getDescription().contains("Unexpected DB error"));
    }

    @Test
    void shouldNotLeakExceptionMessageOnInternalError() {
        when(verificationService.verify(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("SELECT * FROM accounts WHERE id = 'ACC-001'; -- internal detail"));

        impl.verifyAccount(buildRequest("TXN-004", "ACC-001", "ACC-002", "50.00", "USD"),
                responseObserver);

        var errorCaptor = org.mockito.ArgumentCaptor.forClass(Throwable.class);
        verify(responseObserver).onError(errorCaptor.capture());
        var sre = (io.grpc.StatusRuntimeException) errorCaptor.getValue();
        assertEquals(io.grpc.Status.Code.INTERNAL, sre.getStatus().getCode());
        assertEquals("Account verification failed. See server logs.", sre.getStatus().getDescription());
        assertFalse(sre.getStatus().getDescription().contains("SELECT"));
        assertFalse(sre.getStatus().getDescription().contains("ACC-001"));
    }
}
