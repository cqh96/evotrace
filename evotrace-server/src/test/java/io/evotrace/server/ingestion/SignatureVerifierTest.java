package io.evotrace.server.ingestion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignatureVerifierTest {

    private final SignatureVerifier verifier = new SignatureVerifier();

    @Test
    void signThenVerifyRoundTrip() {
        String body = "{\"eventId\":\"evt-1\",\"eventType\":\"COMMIT\"}";
        String secret = "test-api-secret";
        String signature = verifier.sign(body, secret);

        assertTrue(verifier.verify(body, secret, signature));
    }

    @Test
    void verifyRejectsTamperedBody() {
        String secret = "test-api-secret";
        String signature = verifier.sign("original-body", secret);

        assertFalse(verifier.verify("tampered-body", secret, signature));
    }

    @Test
    void verifyRejectsWrongSecret() {
        String body = "payload";
        String signature = verifier.sign(body, "secret-a");

        assertFalse(verifier.verify(body, "secret-b", signature));
    }

    @Test
    void verifyRejectsMalformedSignature() {
        assertFalse(verifier.verify("payload", "secret", "not-hex"));
        assertFalse(verifier.verify("payload", "secret", ""));
        assertFalse(verifier.verify("payload", "secret", "zz".repeat(32)));
    }

    @Test
    void signIsDeterministic() {
        assertEquals(verifier.sign("body", "secret"), verifier.sign("body", "secret"));
        assertNotEquals(verifier.sign("body", "secret"), verifier.sign("body2", "secret"));
    }

    @Test
    void signRejectsNullBody() {
        assertThrows(Exception.class, () -> verifier.sign(null, "secret"));
    }
}
