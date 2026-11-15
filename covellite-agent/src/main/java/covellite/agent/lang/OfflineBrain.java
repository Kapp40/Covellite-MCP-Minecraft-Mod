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
                               SocialState state) {
        if (state != null && state.pending() != null) {
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
        if (action == null || action.isBlank() || reading.negated()) {
            return calls;
        }
        String tool = switch (action) {
            case "mine" -> "mine";
            case "chop_tree" -> "chop_tree";
            case "gather" -> tools.contains("gather") ? "gather" : "chop_tree";
            case "craft_item" -> "craft_item";
            case "attack" -> "attack";
            case "follow" -> "follow";
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
        calls.add(new AgentProvider.ToolCall("cove_" + tool, tool, args));
        return calls;
    }

    private Map<String, Object> argumentsFor(String tool, Parser.Reading reading, WorldFacts world,
                                             String player) {
        Map<String, Object> args = new LinkedHashMap<>();
        switch (tool) {
            case "mine" -> args.put("what", material(reading));
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

    private static String material(Parser.Reading reading) {
        if (reading.items().contains("hierro")) {
            return "iron";
        }
        if (reading.items().contains("diamante")) {
            return "diamond";
        }
        if (reading.items().contains("carbon")) {
            return "coal";
        }
        if (reading.items().contains("oro")) {
            return "gold";
        }
        if (reading.items().contains("madera")) {
            return "wood";
        }
        return "stone";
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
            case PREGUNTA_A_COVE -> "PREGUNTA_A_COVE";
            case SALUDO -> "CHARLA";
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
