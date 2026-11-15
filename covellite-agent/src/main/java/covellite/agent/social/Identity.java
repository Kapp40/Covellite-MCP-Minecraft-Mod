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
 * Cove's evolving identity: traits that shift with what actually happens,
 * opinions it formed, and a bounded internal state.
 *
 * <p>This is not an emotion model and it is not claimed to be one. It is a
 * small numeric state that makes the same personality drift with experience
 * ("cauto despues de dos muertes") and gives the language model something
 * concrete to express instead of a fixed script.</p>
 */
public final class Identity {

    /** Experiences that move the traits. */
    public enum Outcome {
        EXITO, FALLO, DESCUBRIMIENTO, BROMA, PELIGRO, CORRECCION, CHARLA, AYUDA
    }

    private static final Map<String, Double> INITIAL = new LinkedHashMap<>();

    static {
        INITIAL.put("curiosidad", 0.55D);
        INITIAL.put("humor", 0.50D);
        INITIAL.put("cautela", 0.50D);
        INITIAL.put("iniciativa", 0.55D);
        INITIAL.put("apego", 0.50D);
        INITIAL.put("confianza", 0.55D);
    }

    private final Path file;
    private final Map<String, Double> traits = new LinkedHashMap<>(INITIAL);
    private final List<String> opinions = new ArrayList<>();
    private String mood = "neutro";
    private String moodReason = "";
    private long moodUntil;
    private long experiences;
    private long successes;
    private long failures;
    private long discoveries;

    public Identity(Path file) {
        this.file = file;
        load();
    }

    public synchronized double trait(String name) {
        return this.traits.getOrDefault(name, 0.5D);
    }

    /**
     * Adopts a trait value read from a prompt. Only used for the ephemeral
     * copy the embedded brain builds when it needs the current tone; the
     * persistent identity is still the one saved in {@code identity.json}.
     */
    public synchronized void setTrait(String name, double value) {
        if (name == null || name.isBlank()) {
            return;
        }
        this.traits.put(name, Math.max(0.0D, Math.min(1.0D, value)));
    }

    public synchronized String mood(long nowMs) {
        return nowMs > this.moodUntil ? "neutro" : this.mood;
    }

    public synchronized String moodReason(long nowMs) {
        return nowMs > this.moodUntil ? "" : this.moodReason;
    }

    /** Feeds one experience into the identity. Unknown outcomes are ignored. */
    public synchronized void observe(Outcome outcome, String detail, long nowMs) {
        if (outcome == null) {
            return;
        }
        this.experiences++;
        switch (outcome) {
            case EXITO -> {
                this.successes++;
                nudge("confianza", 0.03D);
                nudge("iniciativa", 0.015D);
                setMood("satisfecho", detail, nowMs, 120_000L);
            }
            case FALLO -> {
                this.failures++;
                nudge("confianza", -0.03D);
                nudge("cautela", 0.04D);
                setMood("frustrado", detail, nowMs, 180_000L);
            }
            case DESCUBRIMIENTO -> {
                this.discoveries++;
                nudge("curiosidad", 0.05D);
                nudge("iniciativa", 0.02D);
                setMood("curioso", detail, nowMs, 300_000L);
            }
            case BROMA -> {
                nudge("humor", 0.04D);
                nudge("apego", 0.02D);
                setMood("divertido", detail, nowMs, 90_000L);
            }
            case PELIGRO -> {
                nudge("cautela", 0.04D);
                setMood("alerta", detail, nowMs, 90_000L);
            }
            case CORRECCION -> {
                nudge("confianza", -0.01D);
                nudge("apego", 0.01D);
                setMood("atento", detail, nowMs, 120_000L);
            }
            case AYUDA -> {
                nudge("apego", 0.03D);
                setMood("util", detail, nowMs, 90_000L);
            }
            case CHARLA -> {
                nudge("apego", 0.015D);
            }
            default -> {
            }
        }
        save();
    }

    /** Stores a stable opinion, deduplicated and bounded. */
    public synchronized void opinion(String text) {
        if (text == null) {
            return;
        }
        String clean = text.trim();
        if (clean.isEmpty() || clean.length() < 6) {
            return;
        }
        if (clean.length() > 200) {
            clean = clean.substring(0, 200);
        }
        for (String existing : this.opinions) {
            if (existing.equalsIgnoreCase(clean)) {
                return;
            }
        }
        this.opinions.add(clean);
        while (this.opinions.size() > 12) {
            this.opinions.remove(0);
        }
        save();
    }

    public synchronized void setMood(String mood, String reason, long nowMs, long durationMs) {
        if (mood == null || mood.isBlank()) {
            return;
        }
        this.mood = mood.trim().toLowerCase(Locale.ROOT);
        this.moodReason = reason == null ? "" : reason.trim();
        this.moodUntil = nowMs + Math.max(30_000L, durationMs);
        save();
    }

    /** Identity block for the prompts. */
    public synchronized String promptBlock(long nowMs) {
        StringBuilder builder = new StringBuilder("## Identidad\n");
        builder.append("Rasgos (0-1, cambian con la experiencia): ");
        boolean first = true;
        for (Map.Entry<String, Double> trait : this.traits.entrySet()) {
            if (!first) {
                builder.append(", ");
            }
            first = false;
            builder.append(trait.getKey()).append(' ')
                    .append(String.format(Locale.ROOT, "%.2f", trait.getValue()));
        }
        builder.append('\n');
        String currentMood = mood(nowMs);
        builder.append("Estado interno ahora: ").append(currentMood);
        if (!currentMood.equals("neutro")) {
            String reason = moodReason(nowMs);
            if (!reason.isBlank()) {
                builder.append(" (").append(reason).append(')');
            }
        }
        builder.append('\n');
        if (!this.opinions.isEmpty()) {
            builder.append("Opiniones que ya te formaste:\n");
            for (int i = Math.max(0, this.opinions.size() - 6); i < this.opinions.size(); i++) {
                builder.append("- ").append(this.opinions.get(i)).append('\n');
            }
        }
        builder.append("Experiencias: ").append(this.experiences)
                .append(" (exitos ").append(this.successes)
                .append(", fallos ").append(this.failures)
                .append(", descubrimientos ").append(this.discoveries).append(")\n");
        builder.append("Esto no es una emocion sentida: es tu estado interno y tu historia. ")
                .append("Expresalo con hechos, no anuncies que \"sientes\".\n\n");
        return builder.toString();
    }

    public synchronized Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("traits", new LinkedHashMap<>(this.traits));
        map.put("opinions", new ArrayList<>(this.opinions));
        map.put("mood", this.mood);
        map.put("mood_reason", this.moodReason);
        map.put("mood_until", this.moodUntil);
        map.put("experiences", this.experiences);
        map.put("successes", this.successes);
        map.put("failures", this.failures);
        map.put("discoveries", this.discoveries);
        return map;
    }

    public synchronized Map<String, Object> status(long nowMs) {
        Map<String, Object> map = toMap();
        map.put("current_mood", mood(nowMs));
        return map;
    }

    public synchronized void load() {
        if (this.file == null || !Files.exists(this.file)) {
            return;
        }
        try {
            Map<String, Object> map = Json.parseObject(Files.readString(this.file, StandardCharsets.UTF_8));
            if (map.get("traits") instanceof Map<?, ?> rawTraits) {
                for (Map.Entry<?, ?> entry : rawTraits.entrySet()) {
                    if (entry.getValue() instanceof Number number) {
                        this.traits.put(String.valueOf(entry.getKey()), number.doubleValue());
                    }
                }
            }
            if (map.get("opinions") instanceof List<?> rawOpinions) {
                for (Object item : rawOpinions) {
                    this.opinions.add(String.valueOf(item));
                }
            }
            this.mood = map.get("mood") == null ? "neutro" : String.valueOf(map.get("mood"));
            this.moodReason = map.get("mood_reason") == null ? "" : String.valueOf(map.get("mood_reason"));
            this.moodUntil = map.get("mood_until") instanceof Number n ? n.longValue() : 0L;
            this.experiences = map.get("experiences") instanceof Number n ? n.longValue() : 0L;
            this.successes = map.get("successes") instanceof Number n ? n.longValue() : 0L;
            this.failures = map.get("failures") instanceof Number n ? n.longValue() : 0L;
            this.discoveries = map.get("discoveries") instanceof Number n ? n.longValue() : 0L;
        } catch (IOException | RuntimeException e) {
            System.err.println("[agent] cannot read identity " + this.file + ": " + e.getMessage());
        }
    }

    private void nudge(String trait, double delta) {
        double value = this.traits.getOrDefault(trait, 0.5D) + delta;
        this.traits.put(trait, Math.max(0.0D, Math.min(1.0D, value)));
    }

    private void save() {
        if (this.file == null) {
            return;
        }
        try {
            Files.createDirectories(this.file.getParent());
            Files.writeString(this.file, Json.pretty(toMap()), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException | RuntimeException e) {
            System.err.println("[agent] cannot save identity: " + e.getMessage());
        }
    }
}
