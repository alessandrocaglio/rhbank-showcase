package com.showcase.grpc;

import com.showcase.grpc.account.VerifyAccountRequest;
import com.showcase.grpc.account.VerifyAccountResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Confirms that the protobuf-maven-plugin generated usable Java stubs.
 * If this class compiles and runs, the code generation pipeline is working.
 */
class AccountProtoTest {

    @Test
    void verifyAccountRequest_builderSetsAllFields() {
        VerifyAccountRequest request = VerifyAccountRequest.newBuilder()
                .setTransactionId("txn-001")
                .setSourceAccount("ACC-001")
                .setDestinationAccount("ACC-002")
                .setAmount(150.00)
                .setCurrency("USD")
                .build();

        assertEquals("txn-001", request.getTransactionId());
        assertEquals("ACC-001", request.getSourceAccount());
        assertEquals("ACC-002", request.getDestinationAccount());
        assertEquals(150.00, request.getAmount(), 0.001);
        assertEquals("USD", request.getCurrency());
    }

    @Test
    void verifyAccountResponse_approvedPath() {
        VerifyAccountResponse response = VerifyAccountResponse.newBuilder()
                .setApproved(true)
                .build();

        assertTrue(response.getApproved());
        assertTrue(response.getReason().isEmpty());
    }

    @Test
    void verifyAccountResponse_rejectedPath() {
        VerifyAccountResponse response = VerifyAccountResponse.newBuilder()
                .setApproved(false)
                .setReason("Insufficient balance")
                .build();

        assertFalse(response.getApproved());
        assertEquals("Insufficient balance", response.getReason());
    }
}
