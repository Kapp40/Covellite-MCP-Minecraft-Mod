package covellite.bridge;

import covellite.core.json.Json;
import covellite.core.net.BridgeClient;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Correlated request/response channel from the MCP bridge process to the
 * CovelliteAI mod over the authenticated localhost TCP connection.
 *
 * <p>The mod may also emit unsolicited {@code state}/{@code event} messages.
 * Those are logged to stderr and never written to stdout, so stdout stays a
 * clean MCP JSON-RPC stream.</p>
 */
public final class ModConnection implements AutoCloseable {
    private static final long AUTH_TIMEOUT_MS = 15_000L;
    private static final long DEFAULT_TIMEOUT_MS = 15_000L;

    private final String token;
    private final int port;
    private final AtomicLong counter = new AtomicLong();
    private final Map<String, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();

    private volatile BridgeClient client;
    private volatile boolean authenticated;

    public ModConnection(String token, int port) {
        this.token = token;
        this.port = port;
    }

    public synchronized void connect() throws IOException, InterruptedException {
        if (isAuthenticated()) return;
        authenticated = false;
        BridgeClient previous = client;
        if (previous != null) {
            try { previous.close(); } catch (Exception ignored) {}
            client = null;
        }
        BridgeClient c = new BridgeClient(token, port, this::onMessage,
                error -> System.err.println("[bridge] TCP error: " + error.getMessage()));
        client = c;
        c.start();

        long deadline = System.currentTimeMillis() + AUTH_TIMEOUT_MS;
        while (!c.isAuthenticated() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        if (!c.isAuthenticated()) {
            c.close();
            throw new IOException("Could not authenticate to CovelliteAI mod on 127.0.0.1:" + port
                    + " (is the client with the mod running and logged in?)");
        }
        authenticated = true;
        System.err.println("[bridge] authenticated to mod on port " + port);
    }

    public boolean isAuthenticated() {
        return authenticated && client != null && client.isAuthenticated();
    }

    /** Sends a request and waits for its correlated response. */
    public Object call(String method, Map<String, Object> params) {
        return call(method, params, DEFAULT_TIMEOUT_MS);
    }

    public Object call(String method, Map<String, Object> params, long timeoutMs) {
        if (!isAuthenticated()) {
            // Minecraft may have been restarted after the bridge started. Try a
            // short reconnect before failing the call.
            try {
                connect();
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new ModException("mod_not_connected",
                        "CovelliteAI mod is not connected; start Minecraft with the mod and load a world");
            }
        }
        String id = "bridge-" + counter.incrementAndGet();
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("id", id);
            request.put("method", method);
            request.put("params", params == null ? Map.of() : params);
            client.send(Json.stringify(request));

            Map<String, Object> response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            Object error = response.get("error");
            if (error instanceof Map<?, ?> e) {
                String code = e.get("code") == null ? "error" : String.valueOf(e.get("code"));
                String message = e.get("message") == null ? "mod error" : String.valueOf(e.get("message"));
                throw new ModException(code, message);
            }
            return response.getOrDefault("result", Map.of());
        } catch (TimeoutException e) {
            throw new ModException("timeout", "No response from mod for " + method);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModException("interrupted", "Interrupted while waiting for " + method);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new ModException("internal_error", cause == null ? e.getMessage() : cause.getMessage());
        } finally {
            pending.remove(id);
        }
    }

    private void onMessage(Map<String, Object> message) {
        Object id = message.get("id");
        if (id != null) {
            CompletableFuture<Map<String, Object>> future = pending.remove(String.valueOf(id));
            if (future != null) {
                future.complete(message);
                return;
            }
        }
        Object type = message.get("type");
        if ("event".equals(type)) {
            System.err.println("[bridge] event: " + Json.stringify(message));
        } else if ("state".equals(type) || "state_delta".equals(type)) {
            // State is pull-based through get_player_state; ignore push noise.
        } else {
            System.err.println("[bridge] unsolicited message: " + Json.stringify(message));
        }
    }

    @Override
    public synchronized void close() {
        BridgeClient c = client;
        client = null;
        authenticated = false;
        for (CompletableFuture<Map<String, Object>> f : pending.values()) {
            f.completeExceptionally(new IOException("connection closed"));
        }
        pending.clear();
        if (c != null) c.close();
    }

    public static final class ModException extends RuntimeException {
        private final String code;

        public ModException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
