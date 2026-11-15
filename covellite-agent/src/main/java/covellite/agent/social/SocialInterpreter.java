package covellite.agent.social;

import covellite.agent.AgentProvider;
import covellite.agent.AgentSettings;
import covellite.agent.ProviderException;

import java.util.List;

/**
 * Reads a player's message with the language model.
 *
 * <p>No keyword tables: the model receives the pending question, the recent
 * exchanges and the identity, and returns a small JSON verdict. A provider
 * failure returns an "unknown" interpretation and, critically, never resolves
 * or drops the pending question on its own.</p>
 */
public final class SocialInterpreter {

    private final AgentSettings settings;
    private final AgentProvider provider;

    public SocialInterpreter(AgentSettings settings, AgentProvider provider) {
        this.settings = settings;
        this.provider = provider;
    }

    private static final String SCHEMA = """
            Responde SOLO con un objeto JSON, sin texto antes ni despues:
            {"intencion":"ORDEN|RESPUESTA|BROMA|CHARLA|CORRECCION|PREGUNTA_A_COVE|AMBIGUA",
             "responde_pendiente":true|false,
             "dirigido_a_cove":true|false,
             "tema":"2-5 palabras",
             "entendido":"que quiso decir, en una frase",
             "dato_nuevo":"dato concreto que aporta, o vacio"}
            """;

    public Interpretation interpret(String sender, String message, SocialState state, Identity identity) {
        if (message == null || message.isBlank()) {
            return Interpretation.unknown("mensaje vacio");
        }
        long now = System.currentTimeMillis();
        StringBuilder system = new StringBuilder(1024);
        system.append("Sos el oido de Cove, un jugador artificial en Minecraft. ")
                .append("Tu unica tarea es leer UN mensaje del jugador y clasificarlo.\n\n")
                .append("## Reglas\n")
                .append("- Una orden pide una accion: \"corta ese arbol\", \"volve\".\n")
                .append("- Una respuesta contesta algo, incluida una respuesta vaga.\n")
                .append("- Una broma puede parecer un dato falso (\"la base esta en Marte\"): ")
                .append("marcala BROMA y, si contesta la pregunta pendiente, responde_pendiente=true.\n")
                .append("- Una correccion niega o cambia algo dicho antes: CORRECCION.\n")
                .append("- PREGUNTA_A_COVE solo si la pregunta es para Cove y espera su respuesta.\n")
                .append("- Si no podes decidir, AMBIGUA. Nunca inventes el contenido.\n\n")
                .append(SCHEMA).append('\n');

        if (state != null) {
            String pending = state.promptBlock(now);
            if (!pending.isBlank()) {
                system.append(pending);
            }
            String transcript = state.transcript(10);
            if (!transcript.isBlank()) {
                system.append("## Conversacion reciente\n").append(transcript).append('\n');
            }
        }
        if (identity != null) {
            system.append("## Rasgos de Cove (para juzgar el tono)\n")
                    .append("humor ").append(String.format(java.util.Locale.ROOT, "%.2f", identity.trait("humor")))
                    .append(", cautela ").append(String.format(java.util.Locale.ROOT, "%.2f", identity.trait("cautela")))
                    .append('\n');
        }

        String user = "Jugador " + sender + " dice: \"" + message + "\"";
        AgentProvider.Request request = new AgentProvider.Request(
                this.settings.model(), system.toString(),
                List.of(AgentProvider.Message.user(user)), List.of(),
                Math.max(180, Math.min(400, this.settings.maxTokens() / 3)), 0.1D);
        try {
            AgentProvider.Response response = this.provider.complete(request);
            return Interpretation.parse(response.text(), "respuesta ilegible del modelo");
        } catch (ProviderException | RuntimeException e) {
            return Interpretation.unknown("proveedor no disponible: " + e.getMessage());
        }
    }
}
