package io.evotrace.server.ingestion;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Verifies HMAC-SHA256 request signatures.
 * The client signs the raw request body with the API secret,
 * and the server verifies using the stored secret hash.
 */
@Component
public class SignatureVerifier {

    /**
     * Verify that the given signature matches the HMAC-SHA256(body, apiSecret).
     */
    public boolean verify(String body, String apiSecret, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec spec = new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(spec);
            byte[] expected = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            byte[] actual = HexFormat.of().parseHex(signature);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Sign the given body with the API secret, returning the hex HMAC-SHA256.
     * Mirror of {@link #verify} — used by the diagnostics self-check to produce
     * a signature for its own sample request.
     */
    public String sign(String body, String apiSecret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec spec = new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(spec);
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 签名失败", e);
        }
    }
}
