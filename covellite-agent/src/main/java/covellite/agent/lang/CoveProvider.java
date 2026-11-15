package covellite.agent.lang;

import covellite.agent.AgentProvider;
import covellite.agent.social.Identity;
import covellite.agent.social.MindDecision;
import covellite.agent.social.SocialSignals;
import covellite.agent.social.SocialState;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cove's own brain as an {@link AgentProvider}: no HTTP, no API key, no model
 * server, no network. It answers the three prompts the runtime already sends
 * (interpretation, deliberation, normal turn) with the embedded parser,
 * realizer and decision engine.
 *
 * <p>Implementing the same interface means the whole existing wiring (memory,
 * tools, authority, social state, reflection) works unchanged and the external
 * providers stay available for anyone who prefers them.</p>
 */
public final class CoveProvider implements AgentProvider {

    private static final Pattern DICE = Pattern.compile("dice:\\s*\"(.*)\"\\s*$", Pattern.DOTALL);
    private static final Pattern PENDING = Pattern.compile("Preguntaste:\\s*\"([^\"]*)\"");
    private static final Pattern VIDA = Pattern.compile("vida\\s+(\\d+)");
    private static final Pattern COMIDA = Pattern.compile("comida\\s+(\\d+)");
    private static final Pattern JUGADORES = Pattern.compile("jugadores cerca:\\s*(\\d+)");
    private static final Pattern BIOMA = Pattern.compile("bioma\\s+([^;\\n]+)");


    private final OfflineBrain brain = new OfflineBrain();
    private WorldFacts world = WorldFacts.empty();

    @Override
    public String id() {
        return "cove";
    }

    @Override
    public boolean ready() {
        return true;
    }

    @Override
    public Response complete(Request request) {
        String system = request.system() == null ? "" : request.system();
        if (system.contains("\"intencion\"")) {
            return new Response(this.brain.interpretationJson(interpretation(request, system)),
                    List.of(), "stop");
        }
        if (system.contains("\"accion\":\"silencio|hablar|iniciativa\"")) {
            return new Response(this.brain.decisionJson(decision(system)), List.of(), "stop");
        }
        return turn(request, system);
    }

    // ------------------------------------------------------------------
    // Interpretation prompt
    // ------------------------------------------------------------------

    private Parser.Reading interpretation(Request request, String system) {
        String said = lastUserText(request);
        Matcher dice = DICE.matcher(said);
        if (dice.find()) {
            said = dice.group(1);
        }
        SocialState.PendingQuestion pending = pendingFrom(system);
        return this.brain.read(said, this.world, pending);
    }

    private SocialState.PendingQuestion pendingFrom(String system) {
        Matcher asked = PENDING.matcher(system);
        if (!asked.find()) {
            return null;
        }
        String question = asked.group(1);
        String topic = "";
        Matcher topicMatcher = Pattern.compile("Tema:\\s*([^\\n]+)").matcher(system);
        if (topicMatcher.find()) {
            topic = topicMatcher.group(1).trim();
        }
        return new SocialState.PendingQuestion(question, topic, "prompt", "", 0L, 0L, 0);
    }

    // ------------------------------------------------------------------
    // Deliberation prompt
    // ------------------------------------------------------------------

    private MindDecision decision(String system) {
        WorldFacts facts = factsFrom(system);
        SocialSignals signals = new SocialSignals(System.currentTimeMillis(),
                intOf(JUGADORES, system, 1), -1.0D, facts.health(), facts.food(), false,
                facts.night(), false, facts.spawnDistance(), facts.biome(),
                facts.activity(), 0L, 0L);
        return this.brain.decide(facts, signals, identityFrom(system), null);
    }

    private WorldFacts factsFrom(String system) {
        int health = intOf(VIDA, system, (int) this.world.health());
        int food = intOf(COMIDA, system, this.world.food());
        boolean night = system.contains("es de noche");
        boolean far = system.contains("lejos del punto de inicio");
        String biome = match(BIOMA, system);
        WorldFacts base = new WorldFacts(this.world.playerName(), biome, night, health, food,
                far ? 300.0D : this.world.spawnDistance(), 1, this.world.activity(),
                this.world.knownPlaces(), this.world.inventory());
        this.world = base;
        return base;
    }

    /**
     * Tone only. The persistent identity lives in the runtime; the embedded
     * brain reads the same traits from the prompt when it needs to colour a line.
     */
    private static Identity identityFrom(String system) {
        return new Identity(null);
    }

    // ------------------------------------------------------------------
    // Normal turn
    // ------------------------------------------------------------------

    private Response turn(Request request, String system) {
        String last = lastUserText(request);
        List<String> available = request.tools() == null ? List.of()
                : request.tools().stream().map(AgentProvider.ToolSpec::name).toList();
        boolean afterTool = request.messages() != null
                && request.messages().stream().anyMatch(message -> "tool".equals(message.role()));

        this.world = WorldFacts.fromSnapshot(system, this.world);

        if (afterTool) {
            return new Response(this.brain.realizer().orderAck(), List.of(), "stop");
        }
        if (last.startsWith("[Jugador]")) {
            String player = playerFrom(last);
            String said = saidFrom(last);
            Parser.Reading reading = this.brain.read(said, this.world, null);
            if (reading.order()) {
                List<AgentProvider.ToolCall> calls = this.brain.toolsFor(said, this.world, player, available);
                if (!calls.isEmpty()) {
                    return new Response("", calls, "tool_calls");
                }
                return new Response(this.brain.realizer().unsupported(), List.of(), "stop");
            }
            if (reading.act() == Parser.Act.SALUDO) {
                return new Response(this.brain.greet(player), List.of(), "stop");
            }
            return new Response(this.brain.respond(reading, this.world, null), List.of(), "stop");
        }
        if (last.startsWith("[Propia iniciativa]")) {
            String activity = last.substring("[Propia iniciativa]".length()).trim();
            List<AgentProvider.ToolCall> calls = this.brain.toolsFor(activity, this.world, "", available);
            if (!calls.isEmpty()) {
                return new Response(this.brain.realizer().announce(activity), calls, "tool_calls");
            }
            // An initiative it cannot execute stays silent instead of narrating.
            return new Response("", List.of(), "stop");
        }
        if (last.startsWith("[Sistema]")) {
            return new Response(this.brain.greet(this.world.playerName()), List.of(), "stop");
        }
        // Unknown prompt: answer with the current situation instead of hanging.
        return new Response(this.brain.realizer().comment(this.world, null), List.of(), "stop");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String lastUserText(Request request) {
        if (request.messages() == null) {
            return "";
        }
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            AgentProvider.Message message = request.messages().get(i);
            if ("user".equals(message.role())) {
                return message.text() == null ? "" : message.text();
            }
        }
        return "";
    }

    private static String playerFrom(String text) {
        // "[Jugador] Nombre: mensaje"
        int close = text.indexOf(']');
        int colon = text.indexOf(':', close);
        if (close < 0 || colon < 0) {
            return "";
        }
        return text.substring(close + 1, colon).trim();
    }

    private static String saidFrom(String text) {
        int close = text.indexOf(']');
        int colon = text.indexOf(':', close);
        if (colon < 0) {
            return text;
        }
        String firstLine = text.substring(colon + 1);
        int newline = firstLine.indexOf('\n');
        return (newline < 0 ? firstLine : firstLine.substring(0, newline)).trim();
    }

    private static int intOf(Pattern pattern, String text, int fallback) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
    }

    private static String match(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }
}
