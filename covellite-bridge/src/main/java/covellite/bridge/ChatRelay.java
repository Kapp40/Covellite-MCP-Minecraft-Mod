package covellite.bridge;

import covellite.core.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Durable queue of human chat lines relayed by the mod.
 *
 * <p>The mod pushes {@code {"type":"chat_relay","from","message","at"}} the
 * instant a human types something. That message would otherwise be dropped
 * (it has no request id, so it is not a response) and the agent would only see
 * the text the next time it happened to call a tool and read {@code new_chat}.
 * Keeping the lines in a small append-only JSONL file gives two independent
 * consumers:</p>
 *
 * <ul>
 *   <li>the bridge's own {@code get_relayed_chat} tool, which is guaranteed to
 *       work with any MCP client; and</li>
 *   <li>an OpenCode plugin that tails the same file and calls
 *       {@code session.prompt}, i.e. best-effort automatic wake-up.</li>
 * </ul>
 *
 * <p>The sequence number is monotonic across bridge restarts because it is
 * restored from the file. The file is trimmed from the front so it cannot grow
 * without bound, and every write is best effort: a full disk must never make
 * human chat crash the bridge.</p>
 */
public final class ChatRelay {
    /** Hard cap of in-memory/file entries; older lines are dropped from the front. */
    private static final int MAX_ENTRIES = 2000;
    private static final int KEEP_ENTRIES = 1000;
    private static final int MAX_LINE_LENGTH = 4096;

    private final Path file;
    private final List<Map<String, Object>> entries = new ArrayList<>();
    private long lastSeq;
    private boolean writeFailed;

    public ChatRelay() {
        this(Path.of(System.getProperty("user.home"), ".covemcp", "relay.jsonl"));
    }

    public ChatRelay(Path file) {
        this.file = file;
        load();
    }

    public Path file() {
        return file;
    }

    /**
     * Stores one relayed line and prints it to stderr, keeping stdout clean for
     * the MCP JSON-RPC stream.
     */
    public synchronized Map<String, Object> accept(Map<String, Object> message) {
        String from = text(message.get("from"));
        String body = text(message.get("message"));
        long at = message.get("at") instanceof Number number
                ? number.longValue() : System.currentTimeMillis();
        if (body.isBlank()) {
            return Map.of();
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("seq", ++lastSeq);
        entry.put("from", from.isEmpty() ? "?" : from);
        entry.put("message", body);
        entry.put("at", at);
        entries.add(entry);
        trim();
        append(entry);
        System.err.println("[chat] <" + entry.get("from") + "> " + body);
        return entry;
    }

    /** Entries with {@code seq > afterSeq}, oldest first, capped at {@code limit}. */
    public synchronized Map<String, Object> since(long afterSeq, int limit) {
        int max = Math.max(1, Math.min(256, limit));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            Object seq = entry.get("seq");
            if (seq instanceof Number number && number.longValue() > afterSeq) {
                out.add(new LinkedHashMap<>(entry));
                if (out.size() >= max) {
                    break;
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("messages", out);
        result.put("count", out.size());
        result.put("last_seq", lastSeq);
        result.put("file", file.toAbsolutePath().toString());
        return result;
    }

    public synchronized long lastSeq() {
        return lastSeq;
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    Map<String, Object> entry = Json.parseObject(line);
                    Object seq = entry.get("seq");
                    if (!(seq instanceof Number number)) {
                        continue;
                    }
                    lastSeq = Math.max(lastSeq, number.longValue());
                    entries.add(entry);
                } catch (RuntimeException malformed) {
                    // A half-written last line (crash mid-append) must not stop
                    // the bridge from starting.
                }
            }
            trim();
        } catch (IOException e) {
            System.err.println("[bridge] cannot read chat relay " + file + ": " + e.getMessage());
        }
    }

    private void trim() {
        if (entries.size() <= MAX_ENTRIES) {
            return;
        }
        entries.subList(0, entries.size() - KEEP_ENTRIES).clear();
        rewrite();
    }

    private void append(Map<String, Object> entry) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.stringify(entry) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            writeFailed = false;
        } catch (IOException | RuntimeException e) {
            if (!writeFailed) {
                writeFailed = true;
                System.err.println("[bridge] cannot append chat relay " + file + ": " + e.getMessage());
            }
        }
    }

    private void rewrite() {
        try {
            Files.createDirectories(file.getParent());
            StringBuilder builder = new StringBuilder();
            for (Map<String, Object> entry : entries) {
                builder.append(Json.stringify(entry)).append(System.lineSeparator());
            }
            Files.writeString(file, builder.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException | RuntimeException e) {
            System.err.println("[bridge] cannot trim chat relay " + file + ": " + e.getMessage());
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        return text.length() <= MAX_LINE_LENGTH ? text : text.substring(0, MAX_LINE_LENGTH);
    }
}
