package covellite.agent.lang;

import covellite.agent.AgentProvider;
import covellite.agent.social.Identity;
import covellite.agent.social.MindDecision;
import covellite.agent.social.SocialSignals;
import covellite.agent.social.SocialState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The thinking part of the embedded brain: understand, answer, decide and pick
 * a tool. Everything here runs on the mod's own thread with plain Java, in
 * microseconds, and reuses the tools the mod already has.
 */
public final class OfflineBrain {

    private final Parser parser = new Parser();
    private final Realizer realizer = new Realizer();
    private final Learning learning = new Learning();

    /** Vocabulary learned while playing, for the panel and the tests. */
    public Learning learning() {
        return this.learning;
    }

    /**
     * Takes a definition or a question about a word. Returns the line to say,
     * or empty when the message was not about vocabulary at all.
     */
    public java.util.Optional<String> learn(String text) {
        Learning.Result result = this.learning.consume(text);
        return switch (result.kind()) {
            case LEARNED -> java.util.Optional.of(this.realizer.learnedLearning(result.word()));
            case ASKED -> java.util.Optional.of(this.realizer.askMeaning(result.word()));
            default -> java.util.Optional.empty();
        };
    }

    public Realizer realizer() {
        return this.realizer;
    }

    public Parser.Reading read(String text, WorldFacts world, SocialState.PendingQuestion pending) {
        return this.parser.parse(text, world, pending);
    }

    public String greet(String player) {
        return this.realizer.greet(player);
    }

    public String respond(Parser.Reading reading, WorldFacts world, Identity identity) {
        return this.realizer.respond(reading, world, identity);
    }

    /** Decides silence, a line or an initiative from the world and the identity. */
    public MindDecision decide(WorldFacts world, SocialSignals signals, Identity identity,
                               SocialState.PendingQuestion pending) {
        if (pending != null) {
            // Never stack a new question on top of one already waiting.
            return MindDecision.silence();
        }
        if (world.hungry()) {
            return new MindDecision(MindDecision.Action.INICIATIVA, "",
                    "hambre", "comer algo del inventario", "tengo que revisar la comida", "alerta", false);
        }
        if (world.hurt()) {
            return new MindDecision(MindDecision.Action.HABLAR,
                    this.realizer.ask("herida", world, world.playerName()),
                    "herida", "", "no puedo pelear asi", "alerta", false);
        }
        if (world.farFromSpawn()) {
            return new MindDecision(MindDecision.Action.INICIATIVA,
                    this.realizer.ask("lejos_de_casa", world, world.playerName()),
                    "lejos_de_casa", "volver a la base", "me aleje demasiado", "cauteloso", false);
        }
        if (signals != null && signals.separated() && !world.playerName().isBlank()) {
            return new MindDecision(MindDecision.Action.INICIATIVA, "",
                    "separado_del_jugador", "seguir al jugador", "", "alerta", false);
        }
        if (world.night()) {
            return new MindDecision(MindDecision.Action.HABLAR,
                    this.realizer.ask("noche", world, world.playerName()),
                    "noche", "", "", "alerta", true);
        }
        double initiative = identity == null ? 0.5D : identity.trait("iniciativa");
        if (initiative > 0.6D) {
            return new MindDecision(MindDecision.Action.HABLAR,
                    this.realizer.ask("ubicacion", world, world.playerName()),
                    "ubicacion", "", "", "curioso", true);
        }
        return MindDecision.silence();
    }

    /**
     * Maps an understood order to the mod tools that already implement it.
     * Only tools actually registered for this turn are considered.
     */
    public List<AgentProvider.ToolCall> toolsFor(String text, WorldFacts world, String player,
                                                 List<String> available) {
        Parser.Reading reading = read(text, world, null);
        Set<String> tools = new LinkedHashSet<>(available == null ? List.of() : available);
        List<AgentProvider.ToolCall> calls = new ArrayList<>();
        String action = reading.action();
        if ((action == null || action.isBlank()) && !reading.negated()) {
            // Initiative texts are verb phrases, not player chat ("volver a la
            // base", "comer algo"): the verb alone is enough to pick the tool.
            Lexicon.Entry verb = Lexicon.shared().verbIn(Spanish.tokens(text));
            action = verb == null ? "" : verb.action();
        }
        if (action == null || action.isBlank() || reading.negated()) {
            return calls;
        }
        String tool = switch (action) {
            case "mine" -> "mine";
            case "chop_tree" -> "chop_tree";
            case "gather" -> tools.contains("gather") ? "gather" : "chop_tree";
            case "craft_item" -> "craft_item";
            case "attack" -> "attack";
            case "follow", "come" -> "follow";
            case "stay" -> "stay";
            case "stop" -> "stop";
            case "eat" -> "eat";
            case "sleep" -> "sleep";
            case "fish" -> "fish";
            case "plant" -> "plant";
            case "explore_scan" -> "explore_scan";
            case "return_home" -> tools.contains("return_home") ? "return_home" : "explore_scan";
            case "scan" -> "scan";
            default -> "";
        };
        if (tool.isBlank() || !tools.contains(tool)) {
            return calls;
        }
        Map<String, Object> args = argumentsFor(tool, reading, world, player);
        if (args == null) {
            return calls;
        }
        // "veni" es venir ahora y terminar; "seguime" es quedarse pegado.
        if ("come".equals(action)) {
            args.put("once", true);
        }
        calls.add(new AgentProvider.ToolCall("cove_" + tool, tool, args));
        return calls;
    }

    private Map<String, Object> argumentsFor(String tool, Parser.Reading reading, WorldFacts world,
                                             String player) {
        Map<String, Object> args = new LinkedHashMap<>();
        switch (tool) {
            // A chore must have a real amount: the old call let the bridge
            // default to 8 blocks, so mining looked like it "stopped by itself".
            case "mine", "gather" -> {
                args.put("what", material(reading, tool));
                args.put("count", reading.number() == null ? 32
                        : Math.max(1, Math.min(128, reading.number())));
                args.put("radius", 32);
                // Tunnelling is opt-in: only when the player actually asks to
                // dig down, never as a silent side effect of "conseguime hierro".
                boolean tunnel = Lexicon.shared().tunnelIntent(Spanish.normalize(reading.text()));
                args.put("tunnel", tunnel);
                args.put("timeout_ticks", tunnel ? 12000 : 6000);
            }
            case "chop_tree" -> {
                args.put("max_logs", reading.number() == null ? 64
                        : Math.max(1, Math.min(128, reading.number())));
                args.put("radius", 32);
            }
            case "craft_item" -> {
                String item = craftable(reading);
                if (item == null) {
                    return null;
                }
                args.put("item", item);
                args.put("count", reading.number() == null ? 1 : reading.number());
            }
            case "attack" -> {
                String entity = reading.mobs().isEmpty() ? "monstruo" : reading.mobs().get(0);
                args.put("entity", entity);
            }
            case "follow" -> {
                String target = player == null || player.isBlank() ? world.playerName() : player;
                if (target.isBlank()) {
                    return null;
                }
                args.put("player", target);
            }
            default -> {
            }
        }
        return args;
    }

    /**
     * Maps an understood material to the name the mod's tool expects. Names are
     * matched by containment because the learned dictionary carries longer forms
     * ("mena de hierro", "mineral de diamante", "bloque de piedra").
     */
    private static String material(Parser.Reading reading, String tool) {
        String fallback = "gather".equals(tool) ? "wood" : "stone";
        if (reading.items().isEmpty()) {
            return fallback;
        }
        String all = String.join(" ", reading.items());
        if (all.contains("hierro") || all.contains("iron")) {
            return "iron";
        }
        if (all.contains("diamante") || all.contains("diamond")) {
            return "diamond";
        }
        if (all.contains("carbon") || all.contains("coal")) {
            return "coal";
        }
        if (all.contains("oro") || all.contains("gold")) {
            return "gold";
        }
        if (all.contains("redstone")) {
            return "redstone";
        }
        if (all.contains("lapis") || all.contains("lapiz")) {
            return "lapis";
        }
        if (all.contains("cobre") || all.contains("copper")) {
            return "copper";
        }
        if (all.contains("esmeralda") || all.contains("emerald")) {
            return "emerald";
        }
        if (all.contains("madera") || all.contains("tronco") || all.contains("arbol")
                || all.contains("wood") || all.contains("log")) {
            return "wood";
        }
        if (all.contains("tierra") || all.contains("dirt")) {
            return "dirt";
        }
        if (all.contains("arena") || all.contains("sand")) {
            return "sand";
        }
        if (all.contains("grava") || all.contains("gravel")) {
            return "gravel";
        }
        if (all.contains("piedra") || all.contains("stone")) {
            return "stone";
        }
        return fallback;
    }

    private static String craftable(Parser.Reading reading) {
        if (reading.items().isEmpty()) {
            return null;
        }
        return switch (reading.items().get(0).toLowerCase(Locale.ROOT)) {
            case "mesa" -> "crafting_table";
            case "cofre" -> "chest";
            case "antorcha" -> "torch";
            case "espada" -> "wooden_sword";
            case "pico" -> "wooden_pickaxe";
            case "hacha" -> "wooden_axe";
            case "pala" -> "wooden_shovel";
            case "pan" -> "bread";
            default -> null;
        };
    }

    /** JSON for the interpreter prompt (comprehension without a language model). */
    public String interpretationJson(Parser.Reading reading) {
        String intent = switch (reading.act()) {
            case RESPUESTA_SI, RESPUESTA_NO, RESPUESTA_DATO -> "RESPUESTA";
            case BROMA -> "BROMA";
            case CORRECCION -> "CORRECCION";
            case ORDEN -> "ORDEN";
            case PREGUNTA_A_COVE, PREGUNTA_SOBRE_COVE -> "PREGUNTA_A_COVE";
            case SALUDO, PREFERENCIA, SENTIMIENTO, ELOGIO, INSULTO, PLAN -> "CHARLA";
            default -> reading.joke() ? "BROMA" : "CHARLA";
        };
        if (reading.act() == Parser.Act.AMBIGUA) {
            intent = "AMBIGUA";
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("intencion", intent);
        json.put("responde_pendiente", reading.answersPending());
        json.put("dirigido_a_cove", reading.addressed());
        json.put("tema", topic(reading));
        json.put("entendido", understood(reading));
        json.put("dato_nuevo", newInfo(reading));
        return covellite.core.json.Json.stringify(json);
    }

    /** JSON for the deliberation prompt, so the existing Mind wiring keeps working. */
    public String decisionJson(MindDecision decision) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("accion", switch (decision.action()) {
            case HABLAR -> "hablar";
            case INICIATIVA -> "iniciativa";
            default -> "silencio";
        });
        json.put("mensaje", decision.message());
        json.put("motivo", decision.motive());
        json.put("actividad", decision.activity());
        json.put("aprendizaje", decision.learning());
        json.put("animo", decision.mood());
        json.put("pregunta", decision.asksQuestion());
        return covellite.core.json.Json.stringify(json);
    }

    private static String topic(Parser.Reading reading) {
        if (!reading.places().isEmpty()) {
            return reading.places().get(0);
        }
        if (!reading.items().isEmpty()) {
            return reading.items().get(0);
        }
        if (!reading.mobs().isEmpty()) {
            return reading.mobs().get(0);
        }
        return reading.direction().isEmpty() ? "" : "direccion " + reading.direction();
    }

    private static String understood(Parser.Reading reading) {
        String base = switch (reading.act()) {
            case ORDEN -> "te pidio una accion";
            case PREGUNTA_A_COVE -> "te hizo una pregunta";
            case RESPUESTA_SI -> "te dijo que si";
            case RESPUESTA_NO -> "te dijo que no";
            case RESPUESTA_DATO -> "te dio un dato";
            case BROMA -> "esta bromeando";
            case CORRECCION -> "te esta corrigiendo";
            case SALUDO -> "te saludo";
            default -> "te esta hablando";
        };
        String topic = topic(reading);
        return topic.isBlank() ? base : base + " sobre " + topic;
    }

    private static String newInfo(Parser.Reading reading) {
        if (reading.act() == Parser.Act.BROMA) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        if (!reading.direction().isEmpty()) {
            builder.append("direccion ").append(reading.direction());
        }
        if (reading.number() != null) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append("cantidad ").append(reading.number());
        }
        if (!reading.places().isEmpty()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append("lugar ").append(reading.places().get(0));
        }
        return builder.toString();
    }
}
