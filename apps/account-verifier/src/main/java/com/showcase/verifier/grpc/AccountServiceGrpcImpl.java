package com.showcase.verifier.grpc;

import com.showcase.grpc.account.AccountServiceGrpc;
import com.showcase.grpc.account.VerifyAccountRequest;
import com.showcase.grpc.account.VerifyAccountResponse;
import com.showcase.verifier.service.AccountVerificationService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;

import java.math.BigDecimal;

/**
 * gRPC service implementation.
 *
 * vertx.executeBlocking() dispatches the DB work to a Vert.x worker thread (not the
 * IO event-loop thread). Quarkus's JTA manager permits transactions on worker threads;
 * it blocks ONLY on the event-loop thread. This is the idiomatic pattern for bridging
 * blocking/transactional code into a Quarkus+Vert.x gRPC service.
 */
@GrpcService
public class AccountServiceGrpcImpl extends AccountServiceGrpc.AccountServiceImplBase {

    private final AccountVerificationService verificationService;
    private final Vertx vertx;

    @Inject
    public AccountServiceGrpcImpl(AccountVerificationService verificationService,
                                  Vertx vertx) {
        this.verificationService = verificationService;
        this.vertx = vertx;
    }

    @Override
    public void verifyAccount(VerifyAccountRequest request,
                              StreamObserver<VerifyAccountResponse> responseObserver) {
        vertx.executeBlocking(() -> verificationService.verify(
                        request.getTransactionId(),
                        request.getSourceAccount(),
                        request.getDestinationAccount(),
                        new BigDecimal(String.valueOf(request.getAmount())),
                        request.getCurrency()))
                .onComplete(ar -> {
                    if (ar.succeeded()) {
                        var result = ar.result();
                        responseObserver.onNext(VerifyAccountResponse.newBuilder()
                                .setApproved(result.approved())
                                .setReason(result.reason())
                                .build());
                        responseObserver.onCompleted();
                    } else {
                        responseObserver.onError(Status.INTERNAL
                                .withDescription(ar.cause().getMessage())
                                .withCause(ar.cause())
                                .asRuntimeException());
                    }
                });
    }
}
