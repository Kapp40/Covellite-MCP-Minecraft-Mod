package covellite.agent.social;

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

/**
 * Persistent conversational state.
 *
 * <p>The important part is {@link PendingQuestion}: when Cove asks something it
 * stores the question, the topic, the motive and the moment, so a later answer
 * is matched against it instead of being treated as brand new chatter. The
 * question survives new messages, memory trimming and a game restart; only the
 * model's own interpretation (or an expiry) clears it.</p>
 */
public final class SocialState {

    /** What the player's message looks like to Cove. */
    public enum Intent {
        ORDEN, RESPUESTA, BROMA, CHARLA, CORRECCION, PREGUNTA_A_COVE, AMBIGUA, DESCONOCIDA;

        public static Intent parse(String raw) {
            if (raw == null) {
                return DESCONOCIDA;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException e) {
                return DESCONOCIDA;
            }
        }

        public boolean answersSomething() {
            return this == RESPUESTA || this == BROMA || this == CORRECCION;
        }

        public boolean isOrder() {
            return this == ORDEN || this == CORRECCION;
        }
    }

    /** One completed exchange, kept for the short-term social window. */
    public record Exchange(String role, String text, Intent intent, long at) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("role", this.role);
            map.put("text", this.text);
            map.put("intent", this.intent.name());
            map.put("at", this.at);
            return map;
        }

        public static Exchange fromMap(Map<String, Object> map) {
            return new Exchange(str(map.get("role"), "cove"), str(map.get("text"), ""),
                    Intent.parse(str(map.get("intent"), "DESCONOCIDA")),
                    map.get("at") instanceof Number n ? n.longValue() : 0L);
        }
    }

    /** A question Cove asked and is still waiting on. */
    public record PendingQuestion(String text, String topic, String motive, String context,
                                  long askedAt, long conversationId, int reminders) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("text", this.text);
            map.put("topic", this.topic);
            map.put("motive", this.motive);
            map.put("context", this.context);
            map.put("asked_at", this.askedAt);
            map.put("conversation", this.conversationId);
            map.put("reminders", this.reminders);
            return map;
        }

        public static PendingQuestion fromMap(Map<String, Object> map) {
            return new PendingQuestion(str(map.get("text"), ""), str(map.get("topic"), ""),
                    str(map.get("motive"), ""), str(map.get("context"), ""),
                    map.get("asked_at") instanceof Number n ? n.longValue() : 0L,
                    map.get("conversation") instanceof Number n ? n.longValue() : 0L,
                    map.get("reminders") instanceof Number n ? n.intValue() : 0);
        }
    }

    private static final int MAX_EXCHANGES = 24;

    private final Path file;
    private final List<Exchange> exchanges = new ArrayList<>();
    private PendingQuestion pending;
    private long conversations;
    private long lastSpokeAt;
    private long lastInitiatedAt;
    private long lastHumanChatAt;
    private long initiations;
    private long windowStart;
    private long answered;
    private long expired;
    private boolean dirty;

    public SocialState(Path file) {
        this.file = file;
        load();
    }

    public Path file() {
        return this.file;
    }

    public synchronized void rewindWindow(long nowMs) {
        if (nowMs - this.windowStart >= 10L * 60_000L) {
            this.windowStart = nowMs;
            this.initiations = 0;
        }
    }

    public synchronized int initiationsInWindow(long nowMs) {
        rewindWindow(nowMs);
        return (int) this.initiations;
    }

    public synchronized PendingQuestion pending() {
        return this.pending;
    }

    /** Records a human line without touching the pending question. */
    public synchronized void recordHuman(String text, Intent intent, long nowMs) {
        this.lastHumanChatAt = nowMs;
        add(new Exchange("player", text, intent, nowMs));
        save();
    }

    /** Records something Cove said, and starts a new conversation when it opens one. */
    public synchronized void recordCove(String text, Intent intent, boolean initiated, long nowMs) {
        this.lastSpokeAt = nowMs;
        if (initiated) {
            rewindWindow(nowMs);
            this.lastInitiatedAt = nowMs;
            this.initiations++;
            this.conversations++;
        }
        add(new Exchange("cove", text, intent, nowMs));
        save();
    }

    /** Stores the question Cove just asked. Replaces an older unanswered one. */
    public synchronized PendingQuestion ask(String question, String topic, String motive,
                                           String context, long nowMs) {
        if (this.pending != null) {
            this.expired++;
        }
        this.pending = new PendingQuestion(question == null ? "" : question.trim(),
                topic == null ? "" : topic.trim(), motive == null ? "" : motive.trim(),
                context == null ? "" : context.trim(), nowMs, ++this.conversations, 0);
        save();
        return this.pending;
    }

    /** Clears the pending question because the player answered it. */
    public synchronized PendingQuestion answered(long nowMs) {
        PendingQuestion closed = this.pending;
        this.pending = null;
        if (closed != null) {
            this.answered++;
            save();
        }
        return closed;
    }

    /** Clears an old question that was never answered, so it stops shaping replies. */
    public synchronized PendingQuestion expire(long nowMs) {
        PendingQuestion closed = this.pending;
        this.pending = null;
        if (closed != null) {
            this.expired++;
            add(new Exchange("system", "la pregunta quedo sin respuesta", Intent.DESCONOCIDA, nowMs));
            save();
        }
        return closed;
    }

    /** Marks that Cove insisted once on the same unanswered question. */
    public synchronized void reminder() {
        if (this.pending == null) {
            return;
        }
        this.pending = new PendingQuestion(this.pending.text(), this.pending.topic(),
                this.pending.motive(), this.pending.context(), this.pending.askedAt(),
                this.pending.conversationId(), this.pending.reminders() + 1);
        save();
    }

    public synchronized long lastSpokeAt() {
        return this.lastSpokeAt;
    }

    public synchronized long lastHumanChatAt() {
        return this.lastHumanChatAt;
    }

    public synchronized long lastInitiatedAt() {
        return this.lastInitiatedAt;
    }

    public synchronized long conversations() {
        return this.conversations;
    }

    public synchronized List<Exchange> exchanges() {
        return List.copyOf(this.exchanges);
    }

    /** Compact transcript of the last {@code limit} exchanges, oldest first. */
    public synchronized String transcript(int limit) {
        int count = Math.max(0, Math.min(limit, this.exchanges.size()));
        StringBuilder builder = new StringBuilder();
        for (Exchange exchange : this.exchanges.subList(this.exchanges.size() - count, this.exchanges.size())) {
            builder.append("player".equals(exchange.role()) ? "jugador: " : "cove: ")
                    .append(exchange.text()).append('\n');
        }
        return builder.toString();
    }

    /** The block injected into every prompt so the question cannot be forgotten. */
    public synchronized String promptBlock(long nowMs) {
        if (this.pending == null) {
            return "";
        }
        long age = Math.max(0L, nowMs - this.pending.askedAt());
        StringBuilder builder = new StringBuilder();
        builder.append("## Pregunta pendiente (conversacion ").append(this.pending.conversationId()).append(")\n")
                .append("Preguntaste: \"").append(this.pending.text()).append("\"\n");
        if (!this.pending.topic().isBlank()) {
            builder.append("Tema: ").append(this.pending.topic()).append('\n');
        }
        if (!this.pending.motive().isBlank()) {
            builder.append("Por que preguntaste: ").append(this.pending.motive()).append('\n');
        }
        if (!this.pending.context().isBlank()) {
            builder.append("Contexto: ").append(this.pending.context()).append('\n');
        }
        builder.append("Hace ").append(age / 1000L).append(" s y todavia no tenes respuesta. ")
                .append("Si el jugador contesta, aunque sea en broma, tomalo como respuesta. ")
                .append("No repitas la pregunta tal cual.\n\n");
        return builder.toString();
    }

    public synchronized Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("conversations", this.conversations);
        map.put("answered", this.answered);
        map.put("expired", this.expired);
        map.put("last_spoke_at", this.lastSpokeAt);
        map.put("last_initiated_at", this.lastInitiatedAt);
        map.put("last_human_chat_at", this.lastHumanChatAt);
        map.put("initiations_window", this.initiations);
        map.put("window_start", this.windowStart);
        map.put("pending", this.pending == null ? null : this.pending.toMap());
        List<Object> list = new ArrayList<>();
        for (Exchange exchange : this.exchanges) {
            list.add(exchange.toMap());
        }
        map.put("exchanges", list);
        return map;
    }

    public synchronized void load() {
        if (this.file == null || !Files.exists(this.file)) {
            return;
        }
        try {
            Map<String, Object> map = Json.parseObject(Files.readString(this.file, StandardCharsets.UTF_8));
            this.conversations = num(map.get("conversations"));
            this.answered = num(map.get("answered"));
            this.expired = num(map.get("expired"));
            this.lastSpokeAt = num(map.get("last_spoke_at"));
            this.lastInitiatedAt = num(map.get("last_initiated_at"));
            this.lastHumanChatAt = num(map.get("last_human_chat_at"));
            this.initiations = num(map.get("initiations_window"));
            this.windowStart = num(map.get("window_start"));
            if (map.get("pending") instanceof Map<?, ?> rawPending) {
                this.pending = PendingQuestion.fromMap(cast(rawPending));
            }
            if (map.get("exchanges") instanceof List<?> rawList) {
                for (Object item : rawList) {
                    if (item instanceof Map<?, ?> raw) {
                        this.exchanges.add(Exchange.fromMap(cast(raw)));
                    }
                }
            }
            trim();
        } catch (IOException | RuntimeException e) {
            System.err.println("[agent] cannot read social state " + this.file + ": " + e.getMessage());
        }
    }

    private void add(Exchange exchange) {
        this.exchanges.add(exchange);
        trim();
    }

    private void trim() {
        while (this.exchanges.size() > MAX_EXCHANGES) {
            this.exchanges.remove(0);
        }
    }

    private void save() {
        if (this.file == null) {
            return;
        }
        try {
            Files.createDirectories(this.file.getParent());
            Files.writeString(this.file, Json.pretty(toMap()), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            this.dirty = false;
        } catch (IOException | RuntimeException e) {
            this.dirty = true;
            System.err.println("[agent] cannot save social state: " + e.getMessage());
        }
    }

    public synchronized boolean dirty() {
        return this.dirty;
    }

    private static String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static long num(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }
}
