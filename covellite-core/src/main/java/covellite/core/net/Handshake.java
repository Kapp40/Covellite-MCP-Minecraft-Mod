package covellite.core.net;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

/**
 * HMAC-SHA256 challenge-response handshake.
 *
 * Protocol:
 * 1. Server sends a random 32-byte challenge (hex).
 * 2. Client responds with HMAC-SHA256(token, challenge) as hex.
 * 3. Server verifies and sends {"ok":true}; otherwise closes the connection.
 *
 * The shared token is never sent over the wire.
 */
public final class Handshake {
    private static final String ALGORITHM = "HmacSHA256";
    private static final int CHALLENGE_BYTES = 32;

    private Handshake() {}

    public static String generateChallenge(SecureRandom random) {
        byte[] bytes = new byte[CHALLENGE_BYTES];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static String computeResponse(String token, String challenge) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec key = new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            mac.init(key);
            byte[] bytes = mac.doFinal(challenge.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute HMAC", e);
        }
    }

    public static Map<String, Object> ok() {
        return Map.of("ok", true);
    }

    public static Map<String, Object> fail(String reason) {
        return Map.of("ok", false, "error", reason);
    }

    /**
     * Verifies a client response against the expected challenge and token.
     */
    public static boolean verify(String token, String challenge, String response) {
        if (token == null || challenge == null || response == null) {
            return false;
        }
        String expected = computeResponse(token, challenge);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                response.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Creates a random shared token suitable for writing to a file.
     */
    public static String generateToken(SecureRandom random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
