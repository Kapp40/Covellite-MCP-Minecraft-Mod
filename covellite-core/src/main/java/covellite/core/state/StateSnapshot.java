package covellite.core.state;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable snapshot of the AI player's state.
 */
public final class StateSnapshot {
    private final Map<String, Object> data;
    private final long timestamp;

    public StateSnapshot(Map<String, Object> data, long timestamp) {
        this.data = Map.copyOf(data == null ? Map.of() : data);
        this.timestamp = timestamp;
    }

    public Map<String, Object> data() {
        return data;
    }

    public long timestamp() {
        return timestamp;
    }

    public Object get(String key) {
        return data.get(key);
    }
}
