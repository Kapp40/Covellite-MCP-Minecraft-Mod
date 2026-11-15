package covellite.agent.social;

import covellite.core.json.Json;

import java.util.Locale;
import java.util.Map;

/**
 * The model's reading of one player message.
 *
 * <p>Everything here comes from the language model, not from keyword matching:
 * whether the line is an order, an answer, a joke, small talk, a correction, a
 * question aimed at Cove, or too ambiguous to act on.</p>
 */
public record Interpretation(
        SocialState.Intent intent,
        boolean answersPending,
        boolean addressedToCove,
        String topic,
        String understood,
        String newInfo) {

    public static Interpretation unknown(String why) {
        return new Interpretation(SocialState.Intent.DESCONOCIDA, false, false, "",
                why == null ? "" : why, "");
    }

    public boolean correction() {
        return this.intent == SocialState.Intent.CORRECCION;
    }

    public boolean joke() {
        return this.intent == SocialState.Intent.BROMA;
    }

    /** Parses the JSON object the interpreter prompt asks for, tolerating prose around it. */
    public static Interpretation parse(String raw, String fallbackReason) {
        if (raw == null || raw.isBlank()) {
            return unknown(fallbackReason);
        }
        String json = extractObject(raw);
        if (json == null) {
            return unknown(fallbackReason);
        }
        try {
            Map<String, Object> map = Json.parseObject(json);
            SocialState.Intent intent = SocialState.Intent.parse(
                    firstString(map, "intencion", "intent", "intention"));
            boolean answers = bool(map, "responde_pendiente", "answers_pending", "responde");
            boolean directed = bool(map, "dirigido_a_cove", "addressed_to_cove", "dirigido");
            return new Interpretation(intent, answers, directed,
                    clean(firstString(map, "tema", "topic"), 60),
                    clean(firstString(map, "entendido", "understood", "lectura"), 240),
                    clean(firstString(map, "dato_nuevo", "new_info", "dato"), 240));
        } catch (RuntimeException e) {
            return unknown(fallbackReason);
        }
    }

    /** Finds the first balanced {@code {...}} block, ignoring braces inside quotes. */
    public static String extractObject(String raw) {
        int start = raw.indexOf('{');
        if (start < 0) {
            return null;
        }
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return raw.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static String firstString(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    private static boolean bool(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof Boolean flag) {
                return flag;
            }
            if (value instanceof String text) {
                String normalized = text.trim().toLowerCase(Locale.ROOT);
                if (normalized.equals("true") || normalized.equals("si") || normalized.equals("sí")) {
                    return true;
                }
                if (normalized.equals("false") || normalized.equals("no")) {
                    return false;
                }
            }
        }
        return false;
    }

    private static String clean(String value, int max) {
        if (value == null) {
            return "";
        }
        String flat = value.replace('\n', ' ').trim();
        return flat.length() <= max ? flat : flat.substring(0, max);
    }
}
