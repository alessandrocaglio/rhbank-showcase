package com.showcase.verifier.grpc;

import com.showcase.grpc.account.AccountServiceGrpc;
import com.showcase.grpc.account.VerifyAccountRequest;
import com.showcase.grpc.account.VerifyAccountResponse;
import com.showcase.verifier.service.AccountVerificationService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import jakarta.inject.Inject;

import java.math.BigDecimal;

@GrpcService
public class AccountServiceGrpcImpl extends AccountServiceGrpc.AccountServiceImplBase {

    private final AccountVerificationService verificationService;

    @Inject
    public AccountServiceGrpcImpl(AccountVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    @Override
    public void verifyAccount(VerifyAccountRequest request,
                              StreamObserver<VerifyAccountResponse> responseObserver) {
        try {
            var result = verificationService.verify(
                    request.getTransactionId(),
                    request.getSourceAccount(),
                    request.getDestinationAccount(),
                    new BigDecimal(String.valueOf(request.getAmount())),
                    request.getCurrency());

            responseObserver.onNext(VerifyAccountResponse.newBuilder()
                    .setApproved(result.approved())
                    .setReason(result.reason())
                    .build());
            responseObserver.onCompleted();
        } catch (Exception ex) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription(ex.getMessage())
                    .withCause(ex)
                    .asRuntimeException());
        }
    }
}
