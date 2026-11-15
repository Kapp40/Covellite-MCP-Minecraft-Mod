package covellite.core;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Pure decision for "should I keep chopping?".
 *
 * <p>An explicit order always wins. A permissive answer ("si querés", "como
 * quieras", "fijate") is a 50/50 coin flip, but only once per message: the caller
 * passes a seed derived from the message sequence and stores the outcome, so the
 * same message cannot flip twice.</p>
 *
 * <p>No Minecraft and no agent dependency, so the rules are unit tested.</p>
 */
public final class WoodDecision {

    public enum Answer { CONTINUE, STOP, COIN_FLIP, IGNORED }

    public record Decision(Answer answer, boolean continueChopping) {
    }

    private static final List<String> STOP = List.of(
            "no, para", "no para", "para", "pará", "parate", "dejalo", "dejalo ahi",
            "ya esta", "ya está", "basta", "no hace falta", "no sigas", "corta",
            "cortala", "termina", "terminá", "suficiente", "listo", "no, gracias",
            "no gracias", "deja", "dejá");
    private static final List<String> CONTINUE = List.of(
            "si", "sí", "dale", "segui", "seguí", "continua", "continuá", "mandale",
            "mandalé", "sigue", "seguimos", "va", "ok", "oka", "joya", "de una",
            "obvio", "claro", "si, segui", "sí, seguí", "dale, continua", "sigue nomas",
            "segui nomas", "seguí nomás");
    /** A clear continuation, even next to a permissive "si querés". */
    private static final List<String> EXPLICIT_CONTINUE = List.of(
            "dale", "segui", "seguí", "continua", "continuá", "mandale", "mandalé",
            "sigue", "seguimos", "de una", "sigue nomas", "segui nomas");
    private static final List<String> PERMIT = List.of(
            "si queres", "si querés", "como quieras", "como vos quieras", "fijate",
            "fijáte", "vos sabras", "vos sabrás", "lo que quieras", "hace lo que quieras",
            "hacé lo que quieras", "a tu criterio", "decidi vos", "decidí vos",
            "si te parece", "por mi", "esta bien", "está bien");

    private WoodDecision() {
    }

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        return plain.replaceAll("[^a-z0-9áéíóúñ ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** Reads an explicit answer; ambiguous or unfamiliar text is IGNORED. */
    public static Answer classify(String text) {
        String clean = normalize(text);
        if (clean.isEmpty()) {
            return Answer.IGNORED;
        }
        boolean permit = containsPhrase(clean, PERMIT);
        if (containsPhrase(clean, STOP)) {
            return Answer.STOP;
        }
        if (permit) {
            // "dale, si querés" is still a yes; a bare "si querés" is not.
            return containsPhrase(clean, EXPLICIT_CONTINUE) ? Answer.CONTINUE : Answer.COIN_FLIP;
        }
        if (containsPhrase(clean, CONTINUE)) {
            return Answer.CONTINUE;
        }
        return Answer.IGNORED;
    }

    /**
     * @param seed stable per message (for example the chat sequence), so asking
     *             again about the same message does not re-roll
     */
    public static Decision decide(String text, long seed) {
        Answer answer = classify(text);
        return switch (answer) {
            case CONTINUE -> new Decision(answer, true);
            case STOP -> new Decision(answer, false);
            case COIN_FLIP -> new Decision(answer, (Long.hashCode(seed) & 1) == 0);
            default -> new Decision(Answer.IGNORED, false);
        };
    }

    private static boolean containsPhrase(String clean, List<String> phrases) {
        String padded = " " + clean + " ";
        for (String phrase : phrases) {
            String target = " " + normalize(phrase) + " ";
            if (padded.contains(target)) {
                return true;
            }
        }
        return false;
    }
}
