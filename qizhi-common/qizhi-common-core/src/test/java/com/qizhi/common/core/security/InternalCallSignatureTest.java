package com.qizhi.common.core.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InternalCallSignatureTest {

    @Test
    void signatureBindsCallerTimestampNonceMethodAndExactPath() {
        String secret = "unit-test-secret-with-sufficient-length";
        String signature = InternalCallSignature.sign(
                secret, "gateway", "1700000000000", "nonce-1", "POST",
                "/api/v1/workorder/submit");

        assertTrue(InternalCallSignature.verify(signature,
                InternalCallSignature.sign(secret, "gateway", "1700000000000", "nonce-1",
                        "POST", "/api/v1/workorder/submit")));
        assertFalse(InternalCallSignature.verify(signature,
                InternalCallSignature.sign(secret, "gateway", "1700000000000", "nonce-1",
                        "POST", "/api/v1/workorder/other")));
        assertFalse(InternalCallSignature.verify(signature,
                InternalCallSignature.sign(secret, "approve-service", "1700000000000", "nonce-1",
                        "POST", "/api/v1/workorder/submit")));
    }
}
