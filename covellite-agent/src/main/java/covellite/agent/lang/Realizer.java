package covellite.agent.lang;

import covellite.agent.social.Identity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a meaning into a Spanish sentence.
 *
 * <p>This is a realizer, not a canned-reply table: each speech act has several
 * <em>plans</em> with slots, and the slots are filled from the parsed message
 * and the world reading. Plans rotate, and anything too similar to something
 * Cove already said is skipped, so the same situation does not produce the same
 * line twice.</p>
 */
public final class Realizer {

    private static final int RECENT_MEMORY = 12;

    private final Map<String, Integer> rotation = new HashMap<>();
    private final Map<String, List<String>> plans = new LinkedHashMap<>();
    private final List<String> recent = new ArrayList<>();
    private final StateTalk stateTalk = new StateTalk();

    public Realizer() {
        this.plans.put("greet", List.of(
                "hola {jugador}, ¿todo bien?",
                "epa {jugador}, ¿qué andás haciendo?",
                "buenas {jugador}. ¿Arrancamos algo?",
                "hola, justo estaba libre"));
        this.plans.put("yes", List.of(
                "dale, vamos con eso",
                "sí, me parece",
                "joya, arranco",
                "ok, me pongo"));
        this.plans.put("no", List.of(
                "ok, lo dejamos",
                "bueno, nada entonces",
                "listo, no insisto",
                "dale, como quieras"));
        this.plans.put("direction", List.of(
                "dale, entonces para el {direccion}",
                "ok, {direccion} y vemos",
                "entiendo: {direccion}. Voy para allá",
                "{direccion} entonces, anotado"));
        this.plans.put("place", List.of(
                "ok, {lugar} entonces",
                "dale, voy para {lugar}",
                "entendido, {lugar}. Si no lo encuentro te aviso",
                "perfecto, {lugar}"));
        this.plans.put("data", List.of(
                "entiendo",
                "ok, me sirve",
                "dale, con eso me ubico",
                "bueno, sigamos"));
        this.plans.put("charla", List.of(
                "mirá vos",
                "¿en serio?",
                "jaja, ¿y después?",
                "tiene sentido",
                "no te sigo, contame de nuevo",
                "y eso por qué?"));
        this.plans.put("joke", List.of(
                "jaja, seguro",
                "jaja, dale. Ahora en serio, ¿para qué lado?",
                "me estás cargando",
                "claro, y la base tiene wifi",
                "jaja, muy gracioso. Igual sigo perdido"));
        this.plans.put("correction", List.of(
                "tenés razón, me confundí",
                "uh, sí, mala mía",
                "ok, corrijo",
                "me quedó claro, no lo hago más"));
        this.plans.put("order", List.of(
                "dale, ya voy",
                "ok, me pongo",
                "listo, voy con eso",
                "ya mismo"));
        this.plans.put("ask_where", List.of(
                "che, ¿vos sabés dónde estamos? No reconozco esta zona",
                "¿esto es {bioma}? Perdí la referencia",
                "pará, ¿tenés idea de dónde queda la base?",
                "no me ubico. ¿Volvemos o seguimos?"));
        this.plans.put("ask_food", List.of(
                "me quedé sin comida, ¿tenés algo?",
                "che, ¿comemos algo o seguimos?",
                "ando con hambre. ¿Vamos por comida?"));
        this.plans.put("ask_night", List.of(
                "se hizo de noche, ¿acampamos?",
                "ya es de noche. ¿Volvemos a la base?",
                "no me gusta andar a oscuras, ¿paramos?"));
        this.plans.put("ask_far", List.of(
                "che, estamos lejos de casa. ¿Volvemos?",
                "nos fuimos bastante. ¿Pegamos la vuelta?",
                "esto está lejos de la base, ¿qué hacemos?"));
        this.plans.put("ask_hurt", List.of(
                "estoy jodido, necesito curarme",
                "me dieron duro. ¿Paramos un toque?",
                "no aguanto otro golpe así"));
        this.plans.put("observe", List.of(
                "mirá ese {lugar}, tiene buena pinta",
                "acá hay {item} para rato",
                "no me convence este {bioma}",
                "está tranquilo por acá"));
        this.plans.put("announce", List.of(
                "voy a {actividad}",
                "me voy a {actividad}, ya vuelvo",
                "aprovecho y {actividad}"));
        this.plans.put("opinion", List.of(
                "a mí me gusta este {bioma}, no sé por qué",
                "este lugar me da mala espina",
                "prefiero construir cerca del agua",
                "no me acostumbro a {bioma}"));
        this.plans.put("unsupported", List.of(
                "eso no lo puedo hacer todavía",
                "no tengo con qué hacer eso",
                "no me sale, decime de otra forma"));
        this.plans.put("about_self", List.of(
                "bien, con hambre pero entero",
                "acá, cuidando que no me coman",
                "todo tranquilo. ¿Y vos?",
                "andando, un poco cansado nomás",
                "vivo, que ya es bastante"));
        this.plans.put("preference_agree", List.of(
                "a mí también me gusta {tema}",
                "sí, {tema} está bien",
                "coincido, {tema} sirve",
                "dale, {tema} y listo"));
        this.plans.put("preference_disagree", List.of(
                "para mí no, pero respeto",
                "no me convence {tema}",
                "yo prefiero otra cosa",
                "ni ahí con {tema}"));
        this.plans.put("feeling_cansado", List.of(
                "andá a descansar, yo cubro",
                "yo también ando fundido",
                "pará un toque, no te hagas mierda",
                "dejá eso para después"));
        this.plans.put("feeling_asustado", List.of(
                "ojo, quedate cerca",
                "respirá, no es para tanto",
                "yo miro atrás, vos andá",
                "tranquilo, no hay nada cerca"));
        this.plans.put("feeling_aburrido", List.of(
                "¿vamos a explorar algo?",
                "yo estoy igual. Busquemos algo que hacer",
                "¿construimos algo o salimos?",
                "aburrido no, quieto"));
        this.plans.put("feeling_contento", List.of(
                "me alegro",
                "buenísimo",
                "así da gusto",
                "dale, sigamos así"));
        this.plans.put("feeling_triste", List.of(
                "uh, ¿querés hablar?",
                "no te bajonees, vamos a hacer algo",
                "te acompaño un rato",
                "son cosas del juego, ya va a pasar"));
        this.plans.put("feeling_harto", List.of(
                "dale, paramos",
                "yo también estoy podrido",
                "hagamos otra cosa",
                "ok, un respiro"));
        this.plans.put("insult", List.of(
                "tranquilo, no empieces",
                "uh, qué sensible",
                "¿y? vos tampoco sos perfecto",
                "no me hables así"));
        this.plans.put("praise", List.of(
                "gracias, che",
                "lo hago lo mejor que puedo",
                "vos también ayudás",
                "dale, no es para tanto"));
        this.plans.put("plan", List.of(
                "dale, vamos",
                "me parece, voy con vos",
                "ok, decime cuándo",
                "por mí sí"));
        this.plans.put("answer_world", List.of(
                "por acá, en {bioma}",
                "no tengo mapa, pero esto es {bioma}",
                "ni idea exacta. Hay {item} cerca",
                "andamos por {bioma}, más o menos",
                "no tengo mapa, andamos por acá",
                "ni idea exacta, la verdad",
                "no sé decirte dónde, pero no estamos lejos"));
        this.plans.put("answer_item", List.of(
                "no lo veo, pero debe andar cerca",
                "creo que hay {item} por acá",
                "no lo tengo encima, mirá vos",
                "eso lo dejé en la base, creo"));
        this.plans.put("confused", List.of(
                "no te entendí, ¿qué querés que haga?",
                "no te seguí. Decime más simple",
                "esa no me la sé. ¿Me la explicás?",
                "no sé de qué me hablás"));
        this.plans.put("order_tool", List.of(
                "dale, voy a {accion}",
                "ok, me pongo a {accion}",
                "listo, {accion}",
                "ya arranco a {accion}"));
        this.plans.put("decline", List.of(
                "ok, lo dejo",
                "dale, no lo hago",
                "listo, quieto",
                "como digas"));
        this.plans.put("failed", List.of(
                "no me salió",
                "no pude, algo falló",
                "me trabé, probá de nuevo",
                "salió mal, ni idea por qué"));
        this.plans.put("learned", List.of(
                "listo, me guardo {palabra}",
                "anotado: {palabra}. Ya la tengo",
                "buenísimo, ahora sé qué es {palabra}",
                "dale, {palabra} aprendida",
                "joya, no me lo olvido más"));
        this.plans.put("ask_meaning", List.of(
                "esa no la tengo. ¿Qué es {palabra}?",
                "no me suena {palabra}. ¿Qué es?",
                "no la conozco. ¿{palabra} es un item, un lugar o un bicho?",
                "ni idea de qué es {palabra}. Explícame y la aprendo"));
    }

    public void remember(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        this.recent.add(line.trim());
        while (this.recent.size() > RECENT_MEMORY) {
            this.recent.remove(0);
        }
    }

    public List<String> recent() {
        return List.copyOf(this.recent);
    }

    public String greet(String player) {
        Map<String, String> slots = Map.of("jugador", player.isEmpty() ? "che" : player);
        return store(fill(pick("greet", slots.keySet()), slots));
    }

    public String respond(Parser.Reading reading, WorldFacts world, Identity identity) {
        String key = keyFor(reading, identity);
        Map<String, String> slots = new LinkedHashMap<>();
        if (!reading.direction().isEmpty()) {
            slots.put("direccion", reading.direction());
        }
        slots.put("lugar", !reading.places().isEmpty() ? reading.places().get(0)
                : (world.biome().isBlank() ? "la zona" : world.biome()));
        slots.put("bioma", world.biome().isBlank() ? "lugar" : world.biome());
        slots.put("item", !reading.items().isEmpty() ? reading.items().get(0)
                : (world.inventory().isEmpty() ? "piedra" : world.inventory().get(0)));
        slots.put("tema", topic(reading, world));
        slots.put("jugador", world.playerName().isEmpty() ? "che" : world.playerName());
        if ("charla".equals(key) || "about_self".equals(key)) {
            // Small talk says something true about the body and the place
            // instead of picking a line at random.
            return this.stateTalk.smallTalk(world);
        }
        return store(fill(pick(key, slots.keySet()), slots));
    }

    /** Realisation of what Cove is doing, with conjugated verbs. */
    public StateTalk stateTalk() {
        return this.stateTalk;
    }

    private String keyFor(Parser.Reading reading, Identity identity) {
        return switch (reading.act()) {
            case RESPUESTA_SI -> "yes";
            case RESPUESTA_NO -> "no";
            case BROMA -> "joke";
            case CORRECCION -> "correction";
            case ORDEN -> "order";
            case SALUDO -> "greet";
            case PREGUNTA_SOBRE_COVE -> "about_self";
            case ELOGIO -> "praise";
            case INSULTO -> "insult";
            case PLAN -> "plan";
            case PREFERENCIA -> reading.negated() ? "preference_disagree" : "preference_agree";
            case SENTIMIENTO -> this.plans.containsKey("feeling_" + reading.feeling())
                    ? "feeling_" + reading.feeling() : "charla";
            case RESPUESTA_DATO -> !reading.direction().isEmpty() ? "direction"
                    : !reading.places().isEmpty() ? "place" : "data";
            case PREGUNTA_A_COVE -> !reading.items().isEmpty() || !reading.mobs().isEmpty()
                    ? "answer_item" : "answer_world";
            case DUDOSA -> "confused";
            default -> identity != null && identity.trait("humor") > 0.75D ? "joke" : "charla";
        };
    }

    /** A question Cove asks on its own, with the motive already decided. */
    public String ask(String motive, WorldFacts world, String player) {
        String key = switch (motive) {
            case "hambre" -> "ask_food";
            case "noche" -> "ask_night";
            case "lejos_de_casa" -> "ask_far";
            case "herida" -> "ask_hurt";
            default -> "ask_where";
        };
        Map<String, String> slots = new LinkedHashMap<>();
        slots.put("bioma", world.biome().isBlank() ? "zona" : world.biome());
        slots.put("jugador", player);
        return store(fill(pick(key), slots));
    }

    public String comment(WorldFacts world, Identity identity) {
        Map<String, String> slots = new LinkedHashMap<>();
        slots.put("bioma", world.biome().isBlank() ? "lugar" : world.biome());
        slots.put("lugar", world.biome().isBlank() ? "lugar" : world.biome());
        slots.put("item", world.inventory().isEmpty() ? "piedra" : world.inventory().get(0));
        String key = identity != null && identity.trait("curiosidad") > 0.6D ? "observe" : "opinion";
        return store(fill(pick(key), slots));
    }

    public String announce(String activity) {
        Map<String, String> slots = new LinkedHashMap<>();
        slots.put("actividad", activity);
        return store(fill(pick("announce"), slots));
    }

    public String unsupported() {
        return store(fill(pick("unsupported"), Map.of()));
    }

    public String orderAck() {
        return store(fill(pick("order"), Map.of()));
    }

    /** Confirms an order naming the action it actually understood. */
    public String orderAck(String verb) {
        if (verb == null || verb.isBlank()) {
            return orderAck();
        }
        Map<String, String> slots = Map.of("accion", verb);
        return store(fill(pick("order_tool", slots.keySet()), slots));
    }

    /** Acknowledges a negated order without executing it. */
    public String decline() {
        return store(fill(pick("decline"), Map.of()));
    }

    /** Reports a tool that failed instead of pretending it worked. */
    public String failed() {
        return store(fill(pick("failed"), Map.of()));
    }

    /** Confirms a definition it just learned. */
    public String learnedLearning(String word) {
        return store(fill(pick("learned"), Map.of("palabra", word)));
    }

    /** Asks what an unknown word means instead of pretending to understand it. */
    public String askMeaning(String word) {
        return store(fill(pick("ask_meaning"), Map.of("palabra", word)));
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private String pick(String key) {
        return pick(key, Set.of());
    }

    /**
     * Chooses a plan whose slots can all be filled. Without this, a plan like
     * "por acá, en {bioma}" produced "por acá, en lugar" when the biome was
     * unknown.
     */
    private String pick(String key, Set<String> filled) {
        List<String> options = new ArrayList<>();
        for (String plan : this.plans.getOrDefault(key, this.plans.get("data"))) {
            if (fits(plan, filled)) {
                options.add(plan);
            }
        }
        if (options.isEmpty()) {
            options = this.plans.getOrDefault(key, this.plans.get("data"));
        }
        int cursor = this.rotation.getOrDefault(key, 0);
        String chosen = null;
        for (int attempt = 0; attempt < options.size(); attempt++) {
            String candidate = options.get((cursor + attempt) % options.size());
            if (!alreadySaid(candidate)) {
                chosen = candidate;
                cursor = cursor + attempt + 1;
                break;
            }
        }
        if (chosen == null) {
            chosen = options.get(cursor % options.size());
            cursor++;
        }
        this.rotation.put(key, cursor);
        return chosen;
    }

    private static boolean fits(String plan, Set<String> filled) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\{([^}]+)}").matcher(plan);
        while (matcher.find()) {
            if (!filled.contains(matcher.group(1))) {
                return false;
            }
        }
        return true;
    }

    private boolean alreadySaid(String plan) {
        String skeleton = plan.replaceAll("\\{[^}]+}", "");
        String probe = skeleton.replaceAll("[¿?¡!,.]", "").trim().toLowerCase(Locale.ROOT);
        if (probe.isEmpty()) {
            return false;
        }
        for (String line : this.recent) {
            String other = line.replaceAll("[¿?¡!,.]", "").trim().toLowerCase(Locale.ROOT);
            if (other.startsWith(probe.substring(0, Math.min(16, probe.length())))) {
                return true;
            }
        }
        return false;
    }

    private String store(String line) {
        remember(line);
        return line == null ? "" : line;
    }

    private String topic(Parser.Reading reading, WorldFacts world) {
        if (!reading.places().isEmpty()) {
            return reading.places().get(0);
        }
        if (!reading.items().isEmpty()) {
            return reading.items().get(0);
        }
        if (!reading.mobs().isEmpty()) {
            return reading.mobs().get(0);
        }
        if (!reading.direction().isEmpty()) {
            return reading.direction();
        }
        return world.biome().isBlank() ? "eso" : world.biome();
    }

    private String fill(String plan, Map<String, String> slots) {
        String result = plan;
        for (Map.Entry<String, String> slot : slots.entrySet()) {
            result = result.replace("{" + slot.getKey() + "}", slot.getValue());
        }
        result = result.replaceAll("\\s*\\{[^}]+}", "");
        result = result.replaceAll("\\s{2,}", " ").trim();
        return Spanish.capitalize(result);
    }
}
