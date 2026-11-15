package covellite.agent.social;

/**
 * Prompt blocks that give the model a real conversational situation instead of
 * a stateless question.
 */
public final class SocialPrompt {

    private SocialPrompt() {
    }

    /** Rules appended to the main system prompt on every turn. */
    public static String rules() {
        return """
                ## Conversacion
                - Sos un jugador mas, no un asistente. Habla como alguien que esta ahi.
                - Si tenes una pregunta pendiente, interpreta la respuesta del jugador y continua desde ahi.
                - Una broma es una respuesta valida: no la trates como una orden ni como un dato literal.
                - No repitas preguntas ni frases que ya usaste en esta conversacion.
                - Si no hay nada relevante que decir, responde corto o nada. El silencio es normal.
                - No inventes recuerdos ni lugares: si no estas seguro, pregunta.
                - Nunca digas que sentis emociones ni que sos consciente; mostra tu estado con hechos y actitudes.

                """;
    }

    /** Identity plus pending question plus the recent social window. */
    public static String block(SocialState state, Identity identity, long nowMs) {
        StringBuilder builder = new StringBuilder();
        if (identity != null) {
            builder.append(identity.promptBlock(nowMs));
        }
        if (state != null) {
            String pending = state.promptBlock(nowMs);
            if (!pending.isBlank()) {
                builder.append(pending);
            }
            String transcript = state.transcript(8);
            if (!transcript.isBlank()) {
                builder.append("## Ultimos intercambios\n").append(transcript).append('\n');
            }
        }
        return builder.toString();
    }

    /** Short human-readable description of the current situation. */
    public static String situation(SocialSignals signals) {
        StringBuilder builder = new StringBuilder();
        builder.append("jugadores cerca: ").append(signals.humansOnline());
        if (signals.nearestHumanDistance() >= 0) {
            builder.append(" (el mas cercano a ").append(Math.round(signals.nearestHumanDistance()))
                    .append(" bloques)");
        }
        builder.append("; vida ").append(Math.round(signals.health()))
                .append("; comida ").append(signals.food());
        if (signals.night()) {
            builder.append("; es de noche");
        }
        if (signals.farFromSpawn()) {
            builder.append("; lejos del punto de inicio (").append(Math.round(signals.spawnDistance()))
                    .append(" bloques)");
        }
        if (signals.separated()) {
            builder.append("; separado del jugador");
        }
        if (!signals.biome().isBlank()) {
            builder.append("; bioma ").append(signals.biome());
        }
        if (!signals.activity().isBlank()) {
            builder.append("; actividad ").append(signals.activity());
        }
        return builder.toString();
    }
}
