package covellite.agent.lang;

import covellite.core.Grammar;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Talks about what is actually happening, with conjugated verbs.
 *
 * <p>Every line here is built from the world reading: the activity the mod
 * reports, health, food, biome and what is in the bag. Nothing is a memorised
 * reply, so "¿qué estás haciendo?" gets a different true answer than "me duele
 * la cabeza".</p>
 */
public final class StateTalk {

    /** Activity token -> {infinitive, object}. The mod sends these tokens. */
    private static final Map<String, String[]> ACTIVITY = new LinkedHashMap<>();

    private static final Map<String, String> ITEMS = new LinkedHashMap<>();

    static {
        ACTIVITY.put("mining", new String[] {"minar", ""});
        ACTIVITY.put("digging", new String[] {"cavar", ""});
        ACTIVITY.put("chopping", new String[] {"talar", ""});
        ACTIVITY.put("wood", new String[] {"talar", ""});
        ACTIVITY.put("expedition", new String[] {"explorar", "una mina"});
        ACTIVITY.put("crafting", new String[] {"craftear", ""});
        ACTIVITY.put("fishing", new String[] {"pescar", ""});
        ACTIVITY.put("sleeping", new String[] {"dormir", ""});
        ACTIVITY.put("walking", new String[] {"caminar", ""});
        ACTIVITY.put("following", new String[] {"seguir", ""});
        ACTIVITY.put("guarding", new String[] {"cuidar", ""});
        ACTIVITY.put("fighting", new String[] {"pelear", ""});
        ACTIVITY.put("eating", new String[] {"comer", ""});
        ACTIVITY.put("building", new String[] {"construir", ""});
        ACTIVITY.put("staying", new String[] {"esperar", ""});
        ACTIVITY.put("working", new String[] {"trabajar", ""});
        ACTIVITY.put("recovering", new String[] {"recuperarme", ""});
        ACTIVITY.put("idle", new String[] {"descansar", ""});

        ITEMS.put("iron", "hierro");
        ITEMS.put("raw_iron", "hierro");
        ITEMS.put("stone", "piedra");
        ITEMS.put("cobblestone", "piedra");
        ITEMS.put("coal", "carbón");
        ITEMS.put("diamond", "diamante");
        ITEMS.put("diamantes", "diamante");
        ITEMS.put("gold", "oro");
        ITEMS.put("raw_gold", "oro");
        ITEMS.put("copper", "cobre");
        ITEMS.put("raw_copper", "cobre");
        ITEMS.put("redstone", "redstone");
        ITEMS.put("lapis", "lapislázuli");
        ITEMS.put("emerald", "esmeralda");
        ITEMS.put("wood", "madera");
        ITEMS.put("logs", "madera");
        ITEMS.put("sand", "arena");
        ITEMS.put("gravel", "grava");
        ITEMS.put("dirt", "tierra");
        ITEMS.put("stone_bricks", "piedra");
    }

    private int turn;

    /**
     * Answers a question about Cove itself, or null when the message is about
     * something else. This is what keeps "¿qué estás haciendo?" from being read
     * as "where is the nearest ore".
     */
    public String reply(String said, WorldFacts world) {
        if (said == null) {
            return null;
        }
        String text = Spanish.normalize(said);
        if (text.contains("como estas") || text.contains("como andas")
                || text.contains("como te sentis") || text.contains("que te pasa")
                || text.contains("como va todo") || text.contains("todo bien")) {
            return status(world);
        }
        if (text.contains("que estas haciendo") || text.contains("que haces")
                || text.contains("que andas haciendo") || text.contains("que estabas haciendo")
                || text.contains("que haces ahora") || text.contains("que andas")
                || text.contains("que estuviste haciendo")) {
            return answerDoing(world);
        }
        if (text.contains("como vamos") || text.contains("como viene")
                || text.contains("como salio") || text.contains("que conseguiste")
                || text.contains("que juntaste") || text.contains("que tenes")) {
            return report(world);
        }
        return null;
    }

    /** What it has on it and what it is doing, for "¿qué conseguiste?". */
    public String report(WorldFacts world) {
        if (world.inventory().isEmpty()) {
            return doing(world) + ", todavía no junté nada";
        }
        StringBuilder line = new StringBuilder("tengo ");
        int shown = 0;
        for (String raw : world.inventory()) {
            if (shown > 0) {
                line.append(", ");
            }
            line.append(itemName(raw));
            if (++shown >= 3) {
                break;
            }
        }
        return line.append("; ").append(doing(world)).toString();
    }

    /** "estoy minando hierro" / "estoy talando" / "estoy descansando". */
    public String doing(WorldFacts world) {
        String token = world.activity() == null ? "" : world.activity().trim();
        if (token.isEmpty()) {
            return "estoy dando una vuelta";
        }
        String[] parts = token.split(":", 2);
        String key = parts[0].toLowerCase(Locale.ROOT);
        String detail = parts.length > 1 ? parts[1].trim() : "";
        String[] entry = ACTIVITY.get(key);
        if (entry == null) {
            return "estoy en eso";
        }
        String verb = Grammar.gerund(entry[0]);
        String object = detail.isEmpty() ? entry[1] : itemName(detail);
        if ("following".equals(key) && !detail.isEmpty()) {
            return "estoy siguiendo a " + detail;
        }
        if (object.isEmpty()) {
            return "estoy " + verb;
        }
        return "estoy " + verb + " " + object;
    }

    /** Answer to "¿qué estás haciendo?" / "¿qué haces?". */
    public String answerDoing(WorldFacts world) {
        String line = doing(world);
        if (world.hurt()) {
            return line + ", pero me queda poca vida";
        }
        if (world.hungry()) {
            return line + ", aunque ya me está rugiendo la panza";
        }
        if (!world.playerName().isBlank()) {
            return line + ". ¿Vos qué andás haciendo?";
        }
        return line + ". Si querés que haga otra cosa, decime";
    }

    /** Answer to "¿cómo estás?" / "¿qué te pasa?". */
    public String status(WorldFacts world) {
        StringBuilder line = new StringBuilder();
        if (world.health() >= 16.0F) {
            line.append("estoy entero");
        } else if (world.health() >= 8.0F) {
            line.append("estoy medio tocado");
        } else {
            line.append("estoy mal herido");
        }
        line.append(", ").append(healthPhrase(world.health()));
        if (world.hungry()) {
            line.append(" y tengo hambre");
        } else if (world.food() >= 18) {
            line.append(" y estoy bien comido");
        }
        String place = placeName(world);
        if (!place.isEmpty()) {
            line.append(". Ando por ").append(place);
        }
        return line.append(". ").append(doing(world)).toString();
    }

    /** Spontaneous talk: one real observation plus a question. */
    public String smallTalk(WorldFacts world) {
        this.turn++;
        String place = placeName(world);
        String doing = doing(world);
        String item = world.inventory().isEmpty() ? "" : itemName(world.inventory().get(0));
        String player = world.playerName().isBlank() ? "che" : world.playerName();
        String[] lines = {
                doing + (place.isEmpty() ? "" : " acá en " + place) + ". ¿Necesitás algo?",
                "che " + player + ", " + doing + ". ¿Vamos a buscar algo?",
                item.isEmpty() ? doing + ". Decime si querés que cambie de plan"
                        : "llevo " + item + " encima y " + doing + ". ¿Te sirve?",
                world.night() ? "está oscureciendo y " + doing + ". ¿Nos metemos en la base?"
                        : doing + ". ¿Hay algo que quieras que junte?",
                place.isEmpty() ? "nada raro por acá. " + doing
                        : "esto de " + place + " está tranquilo. " + doing
        };
        return lines[Math.floorMod(this.turn, lines.length)];
    }

    /** Confirms an order naming what is about to happen. */
    public String afterOrder(String what, WorldFacts world) {
        String token = what == null ? "" : what.trim();
        String[] entry = ACTIVITY.get(token.split(":", 2)[0].toLowerCase(Locale.ROOT));
        if (entry == null) {
            return "listo, me pongo";
        }
        String detail = token.contains(":") ? itemName(token.split(":", 2)[1]) : entry[1];
        String verb = Grammar.firstPerson(entry[0]);
        return detail.isEmpty() ? "listo, " + verb + " ahora"
                : "listo, " + verb + " " + detail + " ahora";
    }

    /** "junté 29 de piedra" after a finished job, or an empty string. */
    public String collected(String what, int count) {
        if (count <= 0) {
            return "";
        }
        String name = what == null || what.isBlank() ? "" : " de " + itemName(what);
        return "junté " + count + name;
    }

    public static String itemName(String raw) {
        if (raw == null) {
            return "";
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        String known = ITEMS.get(key);
        if (known != null) {
            return known;
        }
        // Longest key that the raw id contains: "minecraft:iron_ore" -> hierro.
        String best = "";
        for (Map.Entry<String, String> entry : ITEMS.entrySet()) {
            if (key.contains(entry.getKey()) && entry.getKey().length() > best.length()) {
                best = entry.getValue();
            }
        }
        return best.isEmpty() ? key.replace('_', ' ') : best;
    }

    private static String healthPhrase(float health) {
        int hearts = Math.max(0, Math.min(10, Math.round(health / 2.0F)));
        int points = Math.round(health);
        if (hearts >= 8) {
            return "me quedan " + points + " de vida";
        }
        if (hearts >= 4) {
            return "tengo " + hearts + " corazones";
        }
        return "me quedan " + hearts + " corazones, no me saques de acá";
    }

    private static String placeName(WorldFacts world) {
        String biome = world.biome() == null ? "" : world.biome().trim();
        if (biome.isEmpty()) {
            return "";
        }
        String key = biome.toLowerCase(Locale.ROOT);
        Map<String, String> names = Map.ofEntries(
                Map.entry("plains", "el llano"),
                Map.entry("desert", "el desierto"),
                Map.entry("forest", "el bosque"),
                Map.entry("birch_forest", "el bosque de abedules"),
                Map.entry("taiga", "la taiga"),
                Map.entry("jungle", "la selva"),
                Map.entry("snowy_plains", "la nieve"),
                Map.entry("beach", "la playa"),
                Map.entry("ocean", "el mar"),
                Map.entry("river", "el río"),
                Map.entry("mountains", "la montaña"),
                Map.entry("swamp", "el pantano"),
                Map.entry("savanna", "la sabana"),
                Map.entry("badlands", "las badlands"),
                Map.entry("cave", "la cueva"),
                Map.entry("dripstone_caves", "las cuevas de estalactitas"),
                Map.entry("lush_caves", "las cuevas frondosas"));
        for (Map.Entry<String, String> entry : names.entrySet()) {
            if (key.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "la zona";
    }
}
