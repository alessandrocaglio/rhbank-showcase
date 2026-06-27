package com.showcase.gateway.service;

import com.showcase.gateway.client.AccountVerifierClient;
import com.showcase.gateway.dto.PaymentRequest;
import com.showcase.gateway.dto.PaymentResponse;
import com.showcase.gateway.exception.AccountVerificationException;
import com.showcase.grpc.account.VerifyAccountRequest;
import com.showcase.grpc.account.VerifyAccountResponse;
import io.opentelemetry.api.trace.Span;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class PaymentServiceImpl implements PaymentService {

    private final AccountVerifierClient accountVerifierClient;

    public PaymentServiceImpl(AccountVerifierClient accountVerifierClient) {
        this.accountVerifierClient = accountVerifierClient;
    }

    @Override
    public PaymentResponse initiatePayment(PaymentRequest request) {
        String transactionId = UUID.randomUUID().toString();

        Span span = Span.current();
        span.setAttribute("bank.payment.transaction_id", transactionId);
        span.setAttribute("bank.payment.source_account", request.sourceAccount());
        span.setAttribute("bank.payment.amount", request.amount().toPlainString());
        span.setAttribute("bank.payment.currency", request.currency());

        VerifyAccountRequest grpcRequest = VerifyAccountRequest.newBuilder()
                .setTransactionId(transactionId)
                .setSourceAccount(request.sourceAccount())
                .setDestinationAccount(request.destinationAccount())
                .setAmount(request.amount().toPlainString())
                .setCurrency(request.currency())
                .build();

        VerifyAccountResponse response = accountVerifierClient.verify(grpcRequest);

        if (!response.getApproved()) {
            throw new AccountVerificationException(response.getReason());
        }

        return new PaymentResponse(transactionId, "PENDING", "Payment accepted for processing");
    }
}
