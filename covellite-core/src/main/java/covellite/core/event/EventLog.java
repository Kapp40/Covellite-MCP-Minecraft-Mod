package covellite.core.event;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, thread-safe event log. Events are kept in order and can be queried
 * by sequence number for differential updates.
 */
public final class EventLog {
    private final int capacity;
    private final ConcurrentLinkedDeque<Event> events = new ConcurrentLinkedDeque<>();
    private final AtomicLong sequence = new AtomicLong();

    public EventLog(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.capacity = capacity;
    }

    public Event add(String type, Map<String, Object> payload) {
        Event event = new Event(type, payload, System.currentTimeMillis(), sequence.incrementAndGet());
        events.addLast(event);
        while (events.size() > capacity) {
            events.pollFirst();
        }
        return event;
    }

    /**
     * Returns up to limit events with sequence greater than since.
     */
    public List<Event> since(long since, int limit) {
        List<Event> result = new ArrayList<>();
        for (Event e : events) {
            if (e.sequence() > since) {
                result.add(e);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    public List<Event> recent(int limit) {
        List<Event> result = new ArrayList<>();
        Iterator<Event> it = events.descendingIterator();
        while (it.hasNext()) {
            result.add(0, it.next());
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    public long latestSequence() {
        Event last = events.peekLast();
        return last == null ? 0 : last.sequence();
    }

    public int size() {
        return events.size();
    }

    public void clear() {
        events.clear();
    }
}
