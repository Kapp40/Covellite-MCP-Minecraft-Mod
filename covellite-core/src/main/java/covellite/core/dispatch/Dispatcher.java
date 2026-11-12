package covellite.core.dispatch;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes JSON request messages to handlers by method name.
 *
 * Expected request shape:
 * {
 *   "id": "request-id",
 *   "method": "move",
 *   "params": { ... }
 * }
 *
 * Response shape:
 * {
 *   "id": "request-id",
 *   "result": { ... }
 * }
 * or
 * {
 *   "id": "request-id",
 *   "error": { "code": "...", "message": "..." }
 * }
 */
public final class Dispatcher {
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    private volatile Decorator decorator;

    /** Optional hook that can enrich every successful result (e.g. piggyback new chat). */
    public void setDecorator(Decorator decorator) {
        this.decorator = decorator;
    }

    public void register(String method, Handler handler) {
        handlers.put(Objects.requireNonNull(method), Objects.requireNonNull(handler));
    }

    public Map<String, Object> dispatch(Map<String, Object> message) {
        String id = message.containsKey("id") ? String.valueOf(message.get("id")) : null;
        String method = message.containsKey("method") ? String.valueOf(message.get("method")) : null;

        if (method == null || method.isEmpty()) {
            return error(id, "invalid_request", "missing method");
        }

        Handler handler = handlers.get(method);
        if (handler == null) {
            return error(id, "unknown_method", "No handler registered for " + method);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> params = message.get("params") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();

        try {
            Object result = handler.handle(params);
            Decorator hook = this.decorator;
            if (hook != null && result != null) {
                result = hook.decorate(method, result);
            }
            Map<String, Object> response = new java.util.HashMap<>();
            if (id != null) response.put("id", id);
            response.put("result", result == null ? Map.of() : result);
            return response;
        } catch (IllegalArgumentException e) {
            return error(id, "invalid_params", e.getMessage());
        } catch (Exception e) {
            return error(id, "internal_error", e.getMessage());
        }
    }

    private Map<String, Object> error(String id, String code, String message) {
        Map<String, Object> response = new java.util.HashMap<>();
        if (id != null) response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }

    public interface Handler {
        Object handle(Map<String, Object> params) throws Exception;
    }

    public interface Decorator {
        Object decorate(String method, Object result);
    }
}
