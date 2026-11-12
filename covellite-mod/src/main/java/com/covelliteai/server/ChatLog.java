package com.covelliteai.server;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, thread-safe log of chat and system messages seen by the integrated
 * server.
 *
 * <p>NeoForge fires {@code ServerChatEvent} on the server thread for every chat
 * message a player types, including the human player. This class keeps the last
 * {@code capacity} entries so the AI can read what was said through
 * {@code get_chat_history}, without parsing {@code logs/latest.log} from disk.
 */
public final class ChatLog {

    /** One received message. */
    public record Entry(long sequence, long timestamp, String sender,
                        String message, String type, boolean fromCompanion) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("sequence", this.sequence);
            map.put("timestamp", this.timestamp);
            map.put("sender", this.sender);
            map.put("message", this.message);
            map.put("type", this.type);
            map.put("from_companion", this.fromCompanion);
            return map;
        }
    }

    private final int capacity;
    private final ConcurrentLinkedDeque<Entry> entries = new ConcurrentLinkedDeque<>();
    private final AtomicLong sequence = new AtomicLong();

    public ChatLog(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
    }

    /**
     * Appends one message. {@code type} is usually {@code "chat"} or
     * {@code "system"}.
     */
    public Entry add(String sender, String message, String type, boolean fromCompanion) {
        if (message == null) {
            message = "";
        }
        Entry entry = new Entry(
                this.sequence.incrementAndGet(),
                System.currentTimeMillis(),
                sender == null ? "" : sender,
                message,
                type == null ? "chat" : type,
                fromCompanion);
        this.entries.addLast(entry);
        while (this.entries.size() > this.capacity) {
            this.entries.pollFirst();
        }
        return entry;
    }

    /** Last {@code limit} messages in chronological order (oldest first). */
    public List<Entry> recent(int limit) {
        List<Entry> result = new ArrayList<>();
        Iterator<Entry> it = this.entries.descendingIterator();
        while (it.hasNext() && result.size() < limit) {
            result.add(0, it.next());
        }
        return result;
    }

    /** Up to {@code limit} messages with sequence greater than {@code since}. */
    public List<Entry> since(long since, int limit) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : this.entries) {
            if (entry.sequence() > since) {
                result.add(entry);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    public long latestSequence() {
        Entry last = this.entries.peekLast();
        return last == null ? 0L : last.sequence();
    }

    public int size() {
        return this.entries.size();
    }

    public void clear() {
        this.entries.clear();
    }
}
