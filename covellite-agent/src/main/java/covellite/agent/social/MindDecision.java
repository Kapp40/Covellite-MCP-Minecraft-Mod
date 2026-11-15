package covellite.agent.social;

import covellite.core.json.Json;

import java.util.Locale;
import java.util.Map;

/**
 * One decision produced by Cove's own deliberation cycle.
 *
 * <p>Silence is a first-class answer: most cycles end in {@link Action#SILENCIO}
 * because saying nothing relevant is better than filling the chat.</p>
 */
public record MindDecision(
        Action action,
        String message,
        String motive,
        String activity,
        String learning,
        String mood,
        boolean asksQuestion) {

    public enum Action {
        SILENCIO, HABLAR, INICIATIVA;

        static Action parse(String raw) {
            if (raw == null) {
                return SILENCIO;
            }
            String value = raw.trim().toLowerCase(Locale.ROOT);
            return switch (value) {
                case "hablar", "decir", "speak", "comentar", "preguntar" -> HABLAR;
                case "iniciativa", "actuar", "act", "hacer" -> INICIATIVA;
                default -> SILENCIO;
            };
        }
    }

    public static MindDecision silence() {
        return new MindDecision(Action.SILENCIO, "", "nada_relevante", "", "", "", false);
    }

    public boolean saysSomething() {
        return !this.message.isBlank();
    }

    public static MindDecision parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return silence();
        }
        String json = Interpretation.extractObject(raw);
        if (json == null) {
            return silence();
        }
        try {
            Map<String, Object> map = Json.parseObject(json);
            Action action = Action.parse(str(map, "accion", "action", "decision"));
            String message = clean(str(map, "mensaje", "message", "texto"), 280);
            String motive = clean(str(map, "motivo", "motive", "razon"), 160);
            String activity = clean(str(map, "actividad", "activity", "tarea", "accion_juego"), 300);
            String learning = clean(str(map, "aprendizaje", "learning", "opinion", "nota"), 200);
            String mood = clean(str(map, "animo", "mood", "estado"), 24).toLowerCase(Locale.ROOT);
            boolean asks = bool(map, "pregunta", "asks_question", "preguntar");
            if (asks && message.isBlank()) {
                asks = false;
            }
            if (action == Action.SILENCIO && activity.isBlank()) {
                return new MindDecision(Action.SILENCIO, "", motive.isBlank() ? "silencio" : motive,
                        "", learning, mood, false);
            }
            if (action == Action.HABLAR && message.isBlank()) {
                return new MindDecision(Action.SILENCIO, "", motive, "", learning, mood, false);
            }
            if (action == Action.INICIATIVA && activity.isBlank() && message.isBlank()) {
                return new MindDecision(Action.SILENCIO, "", motive, "", learning, mood, false);
            }
            return new MindDecision(action, message, motive, activity, learning, mood, asks);
        } catch (RuntimeException e) {
            return silence();
        }
    }

    private static String str(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
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
