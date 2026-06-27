package com.showcase.gateway.client;

import com.showcase.gateway.exception.AccountVerificationException;
import com.showcase.grpc.account.AccountServiceGrpc;
import com.showcase.grpc.account.VerifyAccountRequest;
import com.showcase.grpc.account.VerifyAccountResponse;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class AccountVerifierClient {

    private final AccountServiceGrpc.AccountServiceBlockingStub stub;

    @Value("${app.grpc.account-verifier-timeout-seconds:5}")
    private int timeoutSeconds;

    public AccountVerifierClient(
            @GrpcClient("account-verifier") AccountServiceGrpc.AccountServiceBlockingStub stub) {
        this.stub = stub;
    }

    public VerifyAccountResponse verify(VerifyAccountRequest request) {
        try {
            return stub.withDeadlineAfter(timeoutSeconds, TimeUnit.SECONDS)
                    .verifyAccount(request);
        } catch (io.grpc.StatusRuntimeException ex) {
            throw new AccountVerificationException(
                    "gRPC call to account-verifier failed: " + ex.getStatus().getDescription());
        }
    }
}
