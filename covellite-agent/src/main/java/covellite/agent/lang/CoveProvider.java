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
    private static final Pattern TOPIC = Pattern.compile("Tema:\\s*([^\\n]+)");
    private static final Pattern TRAIT = Pattern.compile(
            "(curiosidad|humor|iniciativa|sociabilidad|cautela)\\s+(\\d+(?:\\.\\d+)?)");

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
        return this.brain.read(said, WorldFacts.fromPrompt(system, this.world), pendingFrom(system));
    }

    private static SocialState.PendingQuestion pendingFrom(String system) {
        Matcher asked = PENDING.matcher(system);
        if (!asked.find()) {
            return null;
        }
        String question = asked.group(1);
        String topic = "";
        Matcher topicMatcher = TOPIC.matcher(system);
        if (topicMatcher.find()) {
            topic = topicMatcher.group(1).trim();
        }
        return new SocialState.PendingQuestion(question, topic, "prompt", "", 0L, 0L, 0);
    }

    // ------------------------------------------------------------------
    // Deliberation prompt
    // ------------------------------------------------------------------

    private MindDecision decision(String system) {
        WorldFacts facts = WorldFacts.fromPrompt(system, this.world);
        this.world = facts;
        SocialSignals signals = new SocialSignals(System.currentTimeMillis(),
                Math.max(1, facts.humansNear()), -1.0D, facts.health(), facts.food(), false,
                facts.night(), false, facts.spawnDistance(), facts.biome(), facts.activity(), 0L, 0L);
        return this.brain.decide(facts, signals, identityFrom(system), pendingFrom(system));
    }

    /** Tone only: the persistent identity stays where the runtime saved it. */
    private static Identity identityFrom(String system) {
        Identity identity = new Identity(null);
        Matcher matcher = TRAIT.matcher(system);
        while (matcher.find()) {
            try {
                identity.setTrait(matcher.group(1), Double.parseDouble(matcher.group(2)));
            } catch (NumberFormatException ignored) {
                // A malformed trait is simply not adopted.
            }
        }
        return identity;
    }

    // ------------------------------------------------------------------
    // Normal turn
    // ------------------------------------------------------------------

    private Response turn(Request request, String system) {
        String last = lastUserText(request);
        List<String> available = request.tools() == null ? List.of()
                : request.tools().stream().map(AgentProvider.ToolSpec::name).toList();

        this.world = WorldFacts.fromPrompt(system, this.world);
        SocialState.PendingQuestion pending = pendingFrom(system);

        // Only a tool result that just arrived ends the turn. A tool result
        // anywhere in the window must NOT hijack later conversation.
        if (isRightAfterTool(request)) {
            String payload = lastToolPayload(request);
            if (payload.contains("\"error\"")) {
                return new Response(this.brain.realizer().failed(), List.of(), "stop");
            }
            // An initiative was already announced before calling the tool.
            if (last.startsWith("[Propia iniciativa]")) {
                return new Response("", List.of(), "stop");
            }
            Parser.Reading done = this.brain.read(saidFrom(last), this.world, pending);
            return new Response(this.brain.realizer().orderAck(done.verb()), List.of(), "stop");
        }
        if (last.startsWith("[Jugador]")) {
            String player = playerFrom(last);
            String said = saidFrom(last);
            // Definitions and "what is X?" are handled before anything else: a
            // taught word must be usable on the very next message.
            java.util.Optional<String> taught = this.brain.learn(said);
            if (taught.isPresent()) {
                return new Response(taught.get(), List.of(), "stop");
            }
            // Questions about Cove itself are answered from its real state, not
            // by guessing what the player meant.
            String state = this.brain.realizer().stateTalk().reply(said, this.world);
            if (state != null) {
                return new Response(state, List.of(), "stop");
            }
            Parser.Reading reading = this.brain.read(said, this.world, pending);
            if (reading.negated() && reading.order()) {
                return new Response(this.brain.realizer().decline(), List.of(), "stop");
            }
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
            return new Response(this.brain.respond(reading, this.world, identityFrom(system)),
                    List.of(), "stop");
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
        // Unknown prompt: say something true about the situation, with a verb,
        // instead of drawing a random line from a list.
        return new Response(this.brain.realizer().stateTalk().smallTalk(this.world),
                List.of(), "stop");
    }

    private static boolean isRightAfterTool(Request request) {
        if (request.messages() == null || request.messages().isEmpty()) {
            return false;
        }
        AgentProvider.Message last = request.messages().get(request.messages().size() - 1);
        return "tool".equals(last.role());
    }

    private static String lastToolPayload(Request request) {
        if (request.messages() == null || request.messages().isEmpty()) {
            return "";
        }
        AgentProvider.Message last = request.messages().get(request.messages().size() - 1);
        return last.text() == null ? "" : last.text();
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
}
