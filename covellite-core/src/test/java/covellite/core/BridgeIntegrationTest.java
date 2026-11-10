package covellite.core;

import covellite.core.json.Json;
import covellite.core.net.BridgeClient;
import covellite.core.net.BridgeServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class BridgeIntegrationTest {

    public static void run() throws Exception {
        String token = "test-token-123";
        int port = 17452;

        List<Map<String, Object>> serverMessages = new ArrayList<>();
        List<Map<String, Object>> clientMessages = new ArrayList<>();
        List<Throwable> serverErrors = new ArrayList<>();
        List<Throwable> clientErrors = new ArrayList<>();

        BridgeServer server = new BridgeServer(token, port, serverMessages::add, serverErrors::add);
        BridgeClient client = new BridgeClient(token, port, clientMessages::add, clientErrors::add);

        server.start();
        client.start();

        // Wait for authentication
        long deadline = System.currentTimeMillis() + 5000;
        while (!client.isAuthenticated() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        T.isTrue("client authenticated", client.isAuthenticated());

        // Client -> Server
        String msg1 = Json.stringify(Map.of("id", "c1", "method", "ping", "params", Map.of()));
        client.send(msg1);
        waitFor(serverMessages, 1, 5000);
        T.equal("server received ping", "ping", serverMessages.get(0).get("method"));

        // Server -> Client
        String reply = Json.stringify(Map.of("id", "c1", "result", Map.of("ok", true)));
        server.send(reply);
        waitFor(clientMessages, 1, 5000);
        T.equal("client received reply id", "c1", clientMessages.get(0).get("id"));

        // Wrong token should fail
        BridgeClient badClient = new BridgeClient("wrong-token", port, m -> {}, clientErrors::add);
        badClient.start();
        Thread.sleep(500);
        T.isTrue("bad client not authenticated", !badClient.isAuthenticated());

        client.close();
        badClient.close();
        server.close();

        T.isTrue("no server errors", serverErrors.isEmpty());
        System.out.println("  BridgeIntegration: all tests passed");
    }

    private static void waitFor(List<?> list, int size, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (list.size() < size && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }
}
