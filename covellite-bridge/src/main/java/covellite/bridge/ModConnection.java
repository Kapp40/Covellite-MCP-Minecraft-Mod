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
 * CoveMCP mod over the authenticated localhost TCP connection.
 *
 * <p>The mod may also emit unsolicited {@code state}/{@code event} messages.
 * Human chat is pushed as {@code chat_relay} and kept by {@link ChatRelay};
 * everything else is logged to stderr and never written to stdout, so stdout
 * stays a clean MCP JSON-RPC stream.</p>
 */
public final class ModConnection implements AutoCloseable {
    private static final long AUTH_TIMEOUT_MS = 15_000L;
    private static final long DEFAULT_TIMEOUT_MS = 15_000L;

    private final String token;
    private final int port;
    private final ChatRelay relay;
    private final AtomicLong counter = new AtomicLong();
    private final Map<String, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();

    private volatile BridgeClient client;
    private volatile boolean authenticated;

    public ModConnection(String token, int port) {
        this(token, port, new ChatRelay());
    }

    public ModConnection(String token, int port, ChatRelay relay) {
        this.token = token;
        this.port = port;
        this.relay = relay;
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
            throw new IOException("Could not authenticate to CoveMCP mod on 127.0.0.1:" + port
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
                        "CoveMCP mod is not connected; start Minecraft with the mod and load a world");
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

    /**
     * Calls a tool and, when it starts an asynchronous action (move/goto or the
     * last step of a batch), polls the mod locally until the action finishes.
     * The model gets the final outcome in a single tool call instead of having
     * to poll {@code get_action_status} itself.
     */
    @SuppressWarnings("unchecked")
    public Object callAndWait(String method, Map<String, Object> params, long maxWaitMs) {
        Object first = call(method, params);
        if (!(first instanceof Map<?, ?> firstMap)) {
            return first;
        }
        Map<String, Object> target = (Map<String, Object>) firstMap;
        Map<String, Object> holder = null;
        if ("batch".equals(method)) {
            if (target.get("results") instanceof java.util.List<?> results && !results.isEmpty()) {
                Object last = results.get(results.size() - 1);
                if (last instanceof Map<?, ?> lastEntry
                        && lastEntry.get("result") instanceof Map<?, ?> lastResult) {
                    holder = (Map<String, Object>) lastResult;
                }
            }
        } else {
            holder = target;
        }
        if (holder == null || !"running".equals(holder.get("state"))
                || !(holder.get("action_id") instanceof String actionId)) {
            return first;
        }

        long deadline = System.currentTimeMillis() + maxWaitMs;
        Map<String, Object> status = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            Object polled = call("get_action_status", Map.of("id", actionId));
            if (polled instanceof Map<?, ?> polledMap) {
                status = (Map<String, Object>) polledMap;
                if (!"running".equals(status.get("state"))) {
                    break;
                }
            }
        }

        Map<String, Object> compact = new LinkedHashMap<>();
        if (!"batch".equals(method) && firstMap.get("new_chat") != null) {
            compact.put("new_chat", firstMap.get("new_chat"));
        }
        // Context the mod attached to the start of the action (e.g. 'go' -> place, 'craft' -> note).
        if (!"batch".equals(method)) {
            for (String key : new String[] {"place", "note"}) {
                if (firstMap.get(key) != null) {
                    compact.put(key, firstMap.get(key));
                }
            }
        }
        if (status != null) {
            for (Map.Entry<String, Object> entry : status.entrySet()) {
                if (!"id".equals(entry.getKey()) && !"method".equals(entry.getKey())) {
                    compact.put(entry.getKey(), entry.getValue());
                }
            }
        }
        if (status == null || "running".equals(status.get("state"))) {
            compact.put("state", "running");
            compact.put("note", "still running after " + (maxWaitMs / 1000) + "s; call stop to cancel");
        }
        if ("batch".equals(method)) {
            Map<String, Object> merged = new LinkedHashMap<>(target);
            merged.put("final", compact);
            return merged;
        }
        return compact;
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
        if ("chat_relay".equals(type)) {
            // Human chat pushed by the mod as it is typed. It has no request
            // id, so it must be intercepted before the generic "unsolicited"
            // branch (and never written to stdout).
            relay.accept(message);
            return;
        }
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
