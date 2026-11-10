package covellite.core;

import covellite.core.net.Handshake;

import java.security.SecureRandom;

public final class HandshakeTest {

    public static void run() {
        testResponseMatches();
        testWrongTokenFails();
        testWrongChallengeFails();
        testRandomToken();
        System.out.println("  Handshake: all tests passed");
    }

    private static void testResponseMatches() {
        SecureRandom random = new SecureRandom();
        String token = "shared-secret-token";
        String challenge = Handshake.generateChallenge(random);
        String response = Handshake.computeResponse(token, challenge);
        T.isTrue("valid response accepted", Handshake.verify(token, challenge, response));
    }

    private static void testWrongTokenFails() {
        SecureRandom random = new SecureRandom();
        String challenge = Handshake.generateChallenge(random);
        String response = Handshake.computeResponse("correct", challenge);
        T.isTrue("wrong token rejected", !Handshake.verify("wrong", challenge, response));
    }

    private static void testWrongChallengeFails() {
        SecureRandom random = new SecureRandom();
        String token = "shared-secret-token";
        String challenge = Handshake.generateChallenge(random);
        String response = Handshake.computeResponse(token, challenge);
        T.isTrue("wrong challenge rejected", !Handshake.verify(token, "other-challenge", response));
    }

    private static void testRandomToken() {
        SecureRandom random = new SecureRandom();
        String token = Handshake.generateToken(random);
        T.isTrue("token is non-empty", !token.isEmpty());
        T.isTrue("token is url-safe", token.matches("^[A-Za-z0-9_-]+$"));
    }
}
