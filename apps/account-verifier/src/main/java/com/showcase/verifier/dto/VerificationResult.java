package com.showcase.verifier.dto;

public record VerificationResult(boolean approved, String reason) {

    public static VerificationResult ofApproved() {
        return new VerificationResult(true, "");
    }

    public static VerificationResult ofRejected(String reason) {
        return new VerificationResult(false, reason);
    }
}
