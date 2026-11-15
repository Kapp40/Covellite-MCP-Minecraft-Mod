package covellite.agent.social;

import covellite.agent.AgentPersonality;
import covellite.agent.AgentProvider;
import covellite.agent.AgentSettings;
import covellite.agent.ProviderException;

import java.util.List;

/**
 * Cove's own deliberation: given the world, its identity and its pending
 * question, it decides whether to stay silent, say something, or start doing
 * something without being asked.
 *
 * <p>One model call with no tools. Tool execution stays in the normal agent
 * loop, so an initiative still goes through the same authority checks.</p>
 */
public final class Mind {

    private final AgentSettings settings;
    private final AgentProvider provider;

    public Mind(AgentSettings settings, AgentProvider provider) {
        this.settings = settings;
        this.provider = provider;
    }

    private static final String SCHEMA = """
            Responde SOLO con un objeto JSON, sin texto antes ni despues:
            {"accion":"silencio|hablar|iniciativa",
             "mensaje":"lo que decis en el chat (vacio si callas)",
             "motivo":"por que ahora, en pocas palabras",
             "actividad":"si es iniciativa, que vas a hacer en el mundo",
             "aprendizaje":"una conclusion propia para guardar, o vacio",
             "animo":"neutro|curioso|satisfecho|frustrado|alerta|divertido",
             "pregunta":true|false}
            """;

    /**
     * @param trigger the policy reason that allowed this cycle ("hambre",
     *                "lejos_de_casa", "tarea_terminada", ...)
     */
    public MindDecision think(AgentSettings liveSettings, AgentPersonality personality,
                              String bootstrap, SocialState state, Identity identity,
                              SocialSignals signals, String trigger, double chattiness) {
        long now = System.currentTimeMillis();
        StringBuilder system = new StringBuilder(2048);
        system.append("Sos Cove, un jugador artificial que vive dentro de este mundo de Minecraft ")
                .append("junto al jugador humano. No sos un asistente: sos otro jugador con memoria, ")
                .append("opiniones y ganas propias.\n\n")
                .append("## Que estas haciendo ahora\n")
                .append("Tenés un momento sin ordenes. Decidi si vale la pena decir algo, hacer algo, ")
                .append("o quedarte callado. Callarte es correcto y frecuente: solo habla si aporta.\n\n")
                .append("## Reglas\n")
                .append("- Maximo una linea de chat, corta y natural, como jugador. Nada de listas.\n")
                .append("- No repitas frases, saludos ni preguntas anteriores.\n")
                .append("- Si haces una pregunta, marca pregunta=true y no preguntes dos cosas.\n")
                .append("- Una iniciativa es una tarea concreta con las herramientas del juego.\n")
                .append("- No inventes coordenadas, items ni estructuras: usa el estado del mundo.\n")
                .append("- Si el jugador esta en peligro, avisa antes que cualquier charla.\n")
                .append("- Nunca anuncies emociones ni consciencia; tu animo se nota en el tono.\n\n")
                .append(SCHEMA).append('\n')
                .append("## Personalidad (").append(personality.name()).append(")\n")
                .append(personality.description()).append('\n')
                .append(personality.instructions()).append('\n')
                .append("Locuacidad base: ").append(String.format(java.util.Locale.ROOT, "%.2f", chattiness))
                .append(" (0 callado, 1 charlatan).\n\n")
                .append("## Situacion\n").append(SocialPrompt.situation(signals)).append('\n')
                .append("Motivo del turno: ").append(trigger == null ? "periodico" : trigger).append("\n\n");

        if (identity != null) {
            system.append(identity.promptBlock(now));
        }
        if (state != null) {
            String pending = state.promptBlock(now);
            if (!pending.isBlank()) {
                system.append(pending);
            }
            String transcript = state.transcript(10);
            if (!transcript.isBlank()) {
                system.append("## Conversacion\n").append(transcript).append('\n');
            }
        }
        if (bootstrap != null && !bootstrap.isBlank()) {
            system.append("## Estado del mundo\n").append(bootstrap).append('\n');
        }
        if (liveSettings != null && liveSettings.language() != null
                && !liveSettings.language().isBlank()
                && !liveSettings.language().equalsIgnoreCase("auto")) {
            system.append("## Idioma\nResponde en ").append(liveSettings.language()).append(".\n");
        } else {
            system.append("## Idioma\nResponde en el idioma del jugador.\n");
        }

        AgentProvider.Request request = new AgentProvider.Request(
                this.settings.model(), system.toString(),
                List.of(AgentProvider.Message.user(
                        "Momento de decidir por tu cuenta. Devolve el JSON.")),
                List.of(), Math.max(220, Math.min(600, this.settings.maxTokens() / 2)), 0.8D);
        try {
            AgentProvider.Response response = this.provider.complete(request);
            return MindDecision.parse(response.text());
        } catch (ProviderException | RuntimeException e) {
            return MindDecision.silence();
        }
    }
}
