package covellite.core.event;

import java.util.Map;
import java.util.Objects;

/**
 * A timestamped game event.
 */
public final class Event {
    private final String type;
    private final Map<String, Object> payload;
    private final long timestamp;
    private final long sequence;

    public Event(String type, Map<String, Object> payload, long timestamp, long sequence) {
        this.type = Objects.requireNonNull(type);
        this.payload = Map.copyOf(payload == null ? Map.of() : payload);
        this.timestamp = timestamp;
        this.sequence = sequence;
    }

    public String type() { return type; }
    public Map<String, Object> payload() { return payload; }
    public long timestamp() { return timestamp; }
    public long sequence() { return sequence; }

    @Override
    public String toString() {
        return "Event{" + type + ", seq=" + sequence + "}";
    }
}
