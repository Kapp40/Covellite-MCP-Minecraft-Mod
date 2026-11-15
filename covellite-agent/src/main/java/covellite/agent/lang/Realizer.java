package covellite.agent.lang;

import covellite.agent.social.Identity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    private static final int RECENT_MEMORY = 10;

    private final Map<String, Integer> rotation = new HashMap<>();
    private final Map<String, List<String>> plans = new LinkedHashMap<>();
    private final List<String> recent = new ArrayList<>();

    public Realizer() {
        // Saludo
        this.plans.put("greet", List.of(
                "hola {jugador}, ¿todo bien?",
                "epa {jugador}, ¿qué andás haciendo?",
                "buenas {jugador}. ¿Arrancamos algo?",
                "hola, justo estaba libre"));
        // Respuesta afirmativa
        this.plans.put("yes", List.of(
                "dale, vamos con eso",
                "sí, me parece",
                "joya, arranco",
                "ok, me pongo"));
        // Respuesta negativa
        this.plans.put("no", List.of(
                "ok, lo dejamos",
                "bueno, nada entonces",
                "listo, no insisto",
                "dale, como quieras"));
        // Respuesta con dirección
        this.plans.put("direction", List.of(
                "dale, entonces para el {direccion}",
                "ok, {direccion} y vemos",
                "entiendo: {direccion}. Voy para allá",
                "{direccion} entonces, anotado"));
        // Respuesta con lugar
        this.plans.put("place", List.of(
                "ok, {lugar} entonces",
                "dale, voy para {lugar}",
                "entendido, {lugar}. Si no lo encuentro te aviso",
                "perfecto, {lugar}"));
        // Respuesta vaga o con dato
        this.plans.put("data", List.of(
                "entiendo",
                "ok, me sirve",
                "dale, con eso me ubico",
                "bueno, sigamos"));
        // Broma del jugador
        this.plans.put("joke", List.of(
                "jaja, seguro",
                "jaja, dale. Ahora en serio, ¿para qué lado?",
                "me estás cargando",
                "claro, y la base tiene wifi",
                "jaja, muy gracioso. Igual sigo perdido"));
        // Corrección
        this.plans.put("correction", List.of(
                "tenés razón, me confundí",
                "uh, sí, mala mía",
                "ok, corrijo",
                "me quedó claro, no lo hago más"));
        // Orden recibida
        this.plans.put("order", List.of(
                "dale, ya voy",
                "ok, me pongo",
                "listo, voy con eso",
                "ya mismo"));
        // Pregunta u observación propia sobre el mundo
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
        return store(fill(pick("greet"), Map.of("jugador", player.isEmpty() ? "che" : player)));
    }

    public String respond(Parser.Reading reading, WorldFacts world, Identity identity) {
        String key = switch (reading.act()) {
            case RESPUESTA_SI -> "yes";
            case RESPUESTA_NO -> "no";
            case BROMA -> "joke";
            case CORRECCION -> "correction";
            case ORDEN -> "order";
            case RESPUESTA_DATO -> !reading.direction().isEmpty() ? "direction"
                    : !reading.places().isEmpty() ? "place" : "data";
            default -> identity != null && identity.trait("humor") > 0.62D ? "joke" : "data";
        };
        Map<String, String> slots = new LinkedHashMap<>();
        if (!reading.direction().isEmpty()) {
            slots.put("direccion", reading.direction());
        }
        if (!reading.places().isEmpty()) {
            slots.put("lugar", reading.places().get(0));
        } else if (!world.biome().isBlank()) {
            slots.put("lugar", world.biome());
        }
        slots.put("jugador", world.playerName().isEmpty() ? "che" : world.playerName());
        return store(fill(pick(key), slots));
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

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private String pick(String key) {
        List<String> options = this.plans.getOrDefault(key, this.plans.get("data"));
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

    private boolean alreadySaid(String plan) {
        String skeleton = plan.replaceAll("\\{[^}]+}", "");
        for (String line : this.recent) {
            String other = line.replaceAll("[¿?¡!,.]", "").trim().toLowerCase(Locale.ROOT);
            String probe = skeleton.replaceAll("[¿?¡!,.]", "").trim().toLowerCase(Locale.ROOT);
            if (!probe.isEmpty() && other.startsWith(probe.substring(0, Math.min(18, probe.length())))) {
                return true;
            }
        }
        return false;
    }

    private String store(String line) {
        if (line != null && !line.isBlank()) {
            this.recent.add(line.trim());
            while (this.recent.size() > RECENT_MEMORY) {
                this.recent.remove(0);
            }
        }
        return line == null ? "" : line;
    }

    private String fill(String plan, Map<String, String> slots) {
        String result = plan;
        for (Map.Entry<String, String> slot : slots.entrySet()) {
            result = result.replace("{" + slot.getKey() + "}", slot.getValue());
        }
        // Any slot left without data is removed together with the clause that used it.
        result = result.replaceAll("\\s*\\{[^}]+}", "");
        result = result.replaceAll("\\s{2,}", " ").trim();
        return Spanish.capitalize(result);
    }


}
