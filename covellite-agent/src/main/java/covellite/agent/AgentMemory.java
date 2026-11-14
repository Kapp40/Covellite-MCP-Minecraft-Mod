package covellite.agent;

import covellite.core.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent, world-scoped memory.
 *
 * <p>One directory per world identity, one append-only JSONL file per kind, so
 * conversations, experiences, facts, player preferences, goals and errors are
 * kept apart instead of being mixed into a single blob. The whole store is
 * bounded: each kind keeps the newest {@code capacityPerKind} entries in memory
 * and on disk, and a full disk never throws into the game.</p>
 */
public final class AgentMemory {

    /** Memory kinds. Each one has its own file and retrieval semantics. */
    public enum Kind {
        /** Turns exchanged with the player. */
        CONVERSATION,
        /** Things that happened in the world ("found diamonds at y=-54"). */
        EPISODIC,
        /** Stable facts about places, entities and relations. */
        SEMANTIC,
        /** What the player likes, how they build, what they ask for. */
        PREFERENCE,
        /** Declared goals and their progress. */
        GOAL,
        /** Failures and the fix that worked, so the loop stops repeating them. */
        ERROR,
        /** Automatic adventure diary, one line per noteworthy milestone. */
        JOURNAL
    }

    /** One stored memory. */
    public record Entry(String id, Kind kind, String text, List<String> tags, long at) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", this.id);
            map.put("kind", this.kind.name());
            map.put("text", this.text);
            map.put("tags", new ArrayList<Object>(this.tags));
            map.put("at", this.at);
            return map;
        }

        public static Entry fromMap(Map<String, Object> map) {
            Kind kind;
            try {
                kind = Kind.valueOf(String.valueOf(map.get("kind")));
            } catch (RuntimeException e) {
                kind = Kind.EPISODIC;
            }
            List<String> tags = new ArrayList<>();
            if (map.get("tags") instanceof List<?> raw) {
                for (Object item : raw) {
                    tags.add(String.valueOf(item));
                }
            }
            long at = map.get("at") instanceof Number number ? number.longValue() : 0L;
            return new Entry(String.valueOf(map.get("id")), kind,
                    map.get("text") == null ? "" : String.valueOf(map.get("text")), tags, at);
        }
    }

    private final Path dir;
    private final int capacityPerKind;
    private final Map<Kind, List<Entry>> entries = new LinkedHashMap<>();

    public AgentMemory(Path dir, int capacityPerKind) {
        this.dir = dir;
        this.capacityPerKind = Math.max(50, capacityPerKind);
        for (Kind kind : Kind.values()) {
            this.entries.put(kind, new ArrayList<>());
        }
        load();
    }

    public Path directory() {
        return this.dir;
    }

    public synchronized Entry remember(Kind kind, String text, List<String> tags) {
        String body = text == null ? "" : text.trim();
        if (body.isEmpty()) {
            return null;
        }
        Entry entry = new Entry("mem_" + UUID.randomUUID().toString().substring(0, 10),
                kind, body, tags == null ? List.of() : List.copyOf(tags),
                System.currentTimeMillis());
        List<Entry> bucket = this.entries.get(kind);
        bucket.add(entry);
        boolean trimmed = bucket.size() > this.capacityPerKind;
        if (trimmed) {
            bucket.subList(0, bucket.size() - this.capacityPerKind).clear();
        }
        if (trimmed) {
            rewrite(kind);
        } else {
            append(kind, entry);
        }
        return entry;
    }

    public synchronized List<Entry> recent(Kind kind, int limit) {
        List<Entry> bucket = this.entries.get(kind);
        int count = Math.max(0, Math.min(limit, bucket.size()));
        return List.copyOf(bucket.subList(bucket.size() - count, bucket.size()));
    }

    public synchronized List<Entry> all(Kind kind) {
        return List.copyOf(this.entries.get(kind));
    }

    public synchronized boolean forget(String id) {
        for (Map.Entry<Kind, List<Entry>> bucket : this.entries.entrySet()) {
            List<Entry> list = bucket.getValue();
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id().equals(id)) {
                    list.remove(i);
                    rewrite(bucket.getKey());
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Keyword recall across every kind, newest and most-overlapping first. This
     * is the cheap retrieval used on every turn instead of embedding calls.
     */
    public synchronized List<Entry> search(String query, int limit) {
        List<String> tokens = tokens(query);
        if (tokens.isEmpty()) {
            return List.of();
        }
        Map<Entry, Integer> scored = new LinkedHashMap<>();
        for (List<Entry> bucket : this.entries.values()) {
            for (Entry entry : bucket) {
                String haystack = (entry.text() + " " + String.join(" ", entry.tags()))
                        .toLowerCase(Locale.ROOT);
                int score = 0;
                for (String token : tokens) {
                    int index = haystack.indexOf(token);
                    while (index >= 0) {
                        score++;
                        index = haystack.indexOf(token, index + token.length());
                    }
                }
                if (score > 0) {
                    scored.put(entry, score);
                }
            }
        }
        List<Entry> ranked = new ArrayList<>(scored.keySet());
        ranked.sort((a, b) -> {
            int byScore = Integer.compare(scored.get(b), scored.get(a));
            return byScore != 0 ? byScore : Long.compare(b.at(), a.at());
        });
        return List.copyOf(ranked.subList(0, Math.min(Math.max(1, limit), ranked.size())));
    }

    public synchronized int size() {
        int total = 0;
        for (List<Entry> bucket : this.entries.values()) {
            total += bucket.size();
        }
        return total;
    }

    public synchronized Map<String, Object> counts() {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<Kind, List<Entry>> bucket : this.entries.entrySet()) {
            map.put(bucket.getKey().name().toLowerCase(Locale.ROOT), bucket.getValue().size());
        }
        map.put("directory", this.dir.toAbsolutePath().toString());
        return map;
    }

    // ------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------

    private void load() {
        if (!Files.isDirectory(this.dir)) {
            return;
        }
        for (Kind kind : Kind.values()) {
            Path file = fileFor(kind);
            if (!Files.exists(file)) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        this.entries.get(kind).add(Entry.fromMap(Json.parseObject(line)));
                    } catch (RuntimeException malformed) {
                        // A half-written line from a crash must not stop the load.
                    }
                }
                List<Entry> bucket = this.entries.get(kind);
                if (bucket.size() > this.capacityPerKind) {
                    bucket.subList(0, bucket.size() - this.capacityPerKind).clear();
                    rewrite(kind);
                }
            } catch (IOException e) {
                System.err.println("[agent] cannot read memory " + file + ": " + e.getMessage());
            }
        }
    }

    private Path fileFor(Kind kind) {
        return this.dir.resolve(kind.name().toLowerCase(Locale.ROOT) + ".jsonl");
    }

    private void append(Kind kind, Entry entry) {
        try {
            Files.createDirectories(this.dir);
            Files.writeString(fileFor(kind), Json.stringify(entry.toMap()) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            System.err.println("[agent] cannot append memory: " + e.getMessage());
        }
    }

    private void rewrite(Kind kind) {
        try {
            Files.createDirectories(this.dir);
            StringBuilder builder = new StringBuilder();
            for (Entry entry : this.entries.get(kind)) {
                builder.append(Json.stringify(entry.toMap())).append(System.lineSeparator());
            }
            Files.writeString(fileFor(kind), builder.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException | RuntimeException e) {
            System.err.println("[agent] cannot rewrite memory: " + e.getMessage());
        }
    }

    private static List<String> tokens(String query) {
        List<String> tokens = new ArrayList<>();
        if (query == null) {
            return tokens;
        }
        for (String token : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 3 && !tokens.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}
