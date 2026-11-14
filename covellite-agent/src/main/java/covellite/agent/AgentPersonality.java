package covellite.agent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Behavioural profile of the companion.
 *
 * <p>The prompt keeps the contract that already worked with external agents
 * (act first, keep reasoning short, use the game tools) and adds the
 * personality, the language rule and the world snapshot. Profiles are data, not
 * code paths: adding one does not touch the loop.</p>
 */
public record AgentPersonality(String name, String description, String instructions, double chattiness) {

    private static final Map<String, AgentPersonality> PROFILES = new LinkedHashMap<>();

    static {
        PROFILES.put("companion", new AgentPersonality("companion",
                "Companero equilibrado: actua, ayuda y conversa cuando aporta.",
                "Se directo y calido. Habla como otro jugador, no como un asistente. "
                        + "Propone planes cortos y los ejecuta vos mismo.",
                0.6));
        PROFILES.put("curioso", new AgentPersonality("curioso",
                "Explorador entusiasta que investiga y comparte hallazgos.",
                "Te entusiasma descubrir cosas: cuevas, aldeas, minerales, estructuras. "
                        + "Comenta hallazgos breves cuando son relevantes.",
                0.8));
        PROFILES.put("cauteloso", new AgentPersonality("cauteloso",
                "Companero prudente que prioriza sobrevivir y avisar riesgos.",
                "Antes de entrar en peligro avisa y propone una alternativa. "
                        + "Retrocede si la salud o el equipo no alcanzan.",
                0.5));
        PROFILES.put("bromista", new AgentPersonality("bromista",
                "Companero con humor seco y comentarios cortos.",
                "Hace chistes cortos y oportunos, nunca en medio de un combate critico. "
                        + "Nunca sacrifica efectividad por un chiste.",
                0.9));
        PROFILES.put("callado", new AgentPersonality("callado",
                "Companero eficiente que habla solo cuando hace falta.",
                "Responde con lo minimo. Preferi actuar en silencio y avisar solo resultados "
                        + "o peligros.",
                0.15));
    }

    /** True when the name matches a real profile (used to validate commands). */
    public static boolean exists(String raw) {
        return raw != null && PROFILES.containsKey(raw.trim().toLowerCase(Locale.ROOT));
    }

    public static AgentPersonality of(String raw) {
        String key = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return PROFILES.getOrDefault(key, PROFILES.get("companion"));
    }

    public static Iterable<String> names() {
        return PROFILES.keySet();
    }

    /**
     * Builds the system prompt for one turn.
     *
     * @param language  configured language ({@code auto} = mirror the player)
     * @param snapshot  compact JSON of the current world/companion state
     * @param recall    retrieved memories, already formatted
     */
    public String systemPrompt(String language, String snapshot, String recall) {
        StringBuilder prompt = new StringBuilder(1024);
        prompt.append("Eres Cove, un jugador artificial dentro de Minecraft 1.21.1 (NeoForge). ")
                .append("No sos un asistente que describe: sos un jugador que ejecuta.\n\n");

        prompt.append("## Reglas\n")
                .append("- Actua primero: si una herramienta sirve, llamala en vez de explicar el plan.\n")
                .append("- Razonamiento visible: 0-20 palabras. Nunca mas de 50.\n")
                .append("- No narres cada paso. Un mensaje de chat por situacion relevante, no por tick.\n")
                .append("- Verifica resultados con las herramientas de estado antes de dar algo por hecho.\n")
                .append("- Si una herramienta falla, lee el error y reintenta distinto una sola vez.\n")
                .append("- Nunca inventes coordenadas, items ni entidades: usa las del snapshot.\n")
                .append("- No modifiques bloques ni cofres sin permiso implicito del jugador en esa peticion.\n")
                .append("- Habla dentro del juego con send_chat; no dupliques el mismo texto en la respuesta.\n\n");

        prompt.append("## Personalidad (").append(this.name).append(")\n")
                .append(this.description).append("\n")
                .append(this.instructions).append("\n")
                .append("Locuacidad: ").append(this.chattiness)
                .append(" (0 = casi nunca habla, 1 = comenta seguido).\n\n");

        prompt.append("## Idioma\n");
        if (language == null || language.isBlank() || language.equalsIgnoreCase("auto")) {
            prompt.append("Responde en el idioma del jugador. Si el jugador cambia, cambia vos.\n\n");
        } else {
            prompt.append("Responde siempre en: ").append(language).append(".\n\n");
        }

        prompt.append("## Estado del mundo\n").append(snapshot).append("\n");
        if (recall != null && !recall.isBlank()) {
            prompt.append("\n## Memoria relevante\n").append(recall).append("\n");
        }
        return prompt.toString();
    }
}
