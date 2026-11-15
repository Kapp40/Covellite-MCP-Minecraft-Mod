package covellite.agent.lang;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Spanish morphology for a self-contained generator.
 *
 * <p>Conjugates every tense a player actually uses when talking casually:
 * present, preterite, imperfect, future, conditional, present subjunctive,
 * voseo imperative, gerund and participle. The point is recall on input: if
 * someone says "talaríamos" or "rompieron", Cove still knows which action they
 * mean, without a model and without a dictionary download.</p>
 */
public final class Spanish {

    public enum Tense {
        PRESENTE, PRETERITO, IMPERFECTO, FUTURO, CONDICIONAL, SUBJUNTIVO,
        IMPERATIVO, GERUNDIO, PARTICIPIO, INFINITIVO
    }

    /** YO, TU is voseo ("vos"), EL is third person singular. */
    public enum Person { YO, TU, EL, NOSOTROS, ELLOS }

    private static final Map<String, String[]> PRESENT_IRREGULAR = new HashMap<>();
    private static final Map<String, String> IMPERATIVE_IRREGULAR = new HashMap<>();
    private static final Map<String, String[]> PRETERITE_IRREGULAR = new HashMap<>();
    private static final Map<String, String> SUBJUNCTIVE_STEM = new HashMap<>();
    private static final Map<String, String> FUTURE_STEM = new HashMap<>();
    private static final Map<String, String[]> IMPERFECT_IRREGULAR = new HashMap<>();

    /**
     * Chat abbreviations a player actually types. They are expanded before any
     * lookup, so "q haces" reaches the same phrase as "que haces" and "xq"
     * carries the meaning of "porque".
     */
    private static final Map<String, String> ABBREVIATIONS = Map.ofEntries(
            Map.entry("q", "que"),
            Map.entry("k", "que"),
            Map.entry("xq", "porque"),
            Map.entry("pq", "porque"),
            Map.entry("xk", "porque"),
            Map.entry("qe", "que"),
            Map.entry("qn", "quien"),
            Map.entry("dnd", "donde"),
            Map.entry("adnd", "adonde"),
            Map.entry("tmb", "tambien"),
            Map.entry("tmbn", "tambien"),
            Map.entry("xfa", "por favor"),
            Map.entry("porfa", "por favor"),
            Map.entry("pf", "por favor"),
            Map.entry("nse", "no se"),
            Map.entry("nose", "no se"),
            Map.entry("pa", "para"),
            Map.entry("pal", "para el"),
            Map.entry("dsp", "despues"),
            Map.entry("dept", "despues"),
            Map.entry("ahre", "ah re"),
            Map.entry("salu2", "saludos"));

    /** Letters typed as numbers ("h0la", "m1na"). Digits alone are counts. */
    private static final Map<Character, Character> LEET = Map.of(
            '0', 'o', '1', 'i', '3', 'e', '4', 'a', '5', 's', '7', 't');

    static {
        PRESENT_IRREGULAR.put("ir", new String[]{"voy", "vas", "va", "vamos", "van"});
        PRESENT_IRREGULAR.put("estar", new String[]{"estoy", "estas", "esta", "estamos", "estan"});
        PRESENT_IRREGULAR.put("ser", new String[]{"soy", "sos", "es", "somos", "son"});
        PRESENT_IRREGULAR.put("tener", new String[]{"tengo", "tenes", "tiene", "tenemos", "tienen"});
        PRESENT_IRREGULAR.put("poder", new String[]{"puedo", "podes", "puede", "podemos", "pueden"});
        PRESENT_IRREGULAR.put("querer", new String[]{"quiero", "queres", "quiere", "queremos", "quieren"});
        PRESENT_IRREGULAR.put("saber", new String[]{"se", "sabes", "sabe", "sabemos", "saben"});
        PRESENT_IRREGULAR.put("hacer", new String[]{"hago", "haces", "hace", "hacemos", "hacen"});
        PRESENT_IRREGULAR.put("venir", new String[]{"vengo", "venis", "viene", "venimos", "vienen"});
        PRESENT_IRREGULAR.put("decir", new String[]{"digo", "decis", "dice", "decimos", "dicen"});
        PRESENT_IRREGULAR.put("ver", new String[]{"veo", "ves", "ve", "vemos", "ven"});
        PRESENT_IRREGULAR.put("dar", new String[]{"doy", "das", "da", "damos", "dan"});
        PRESENT_IRREGULAR.put("haber", new String[]{"he", "has", "ha", "hemos", "han"});
        PRESENT_IRREGULAR.put("poner", new String[]{"pongo", "pones", "pone", "ponemos", "ponen"});
        PRESENT_IRREGULAR.put("traer", new String[]{"traigo", "traes", "trae", "traemos", "traen"});
        PRESENT_IRREGULAR.put("salir", new String[]{"salgo", "salís", "sale", "salimos", "salen"});
        PRESENT_IRREGULAR.put("conocer", new String[]{"conozco", "conoces", "conoce", "conocemos", "conocen"});
        PRESENT_IRREGULAR.put("dormir", new String[]{"duermo", "dormis", "duerme", "dormimos", "duermen"});
        PRESENT_IRREGULAR.put("seguir", new String[]{"sigo", "seguis", "sigue", "seguimos", "siguen"});
        PRESENT_IRREGULAR.put("pedir", new String[]{"pido", "pedis", "pide", "pedimos", "piden"});
        PRESENT_IRREGULAR.put("pensar", new String[]{"pienso", "pensas", "piensa", "pensamos", "piensan"});
        PRESENT_IRREGULAR.put("perder", new String[]{"pierdo", "perdes", "pierde", "perdemos", "pierden"});
        PRESENT_IRREGULAR.put("volver", new String[]{"vuelvo", "volves", "vuelve", "volvemos", "vuelven"});
        PRESENT_IRREGULAR.put("encontrar", new String[]{"encuentro", "encontras", "encuentra", "encontramos", "encuentran"});
        PRESENT_IRREGULAR.put("contar", new String[]{"cuento", "contas", "cuenta", "contamos", "cuentan"});
        PRESENT_IRREGULAR.put("jugar", new String[]{"juego", "jugas", "juega", "jugamos", "juegan"});
        PRESENT_IRREGULAR.put("cerrar", new String[]{"cierro", "cerras", "cierra", "cerramos", "cierran"});
        PRESENT_IRREGULAR.put("preferir", new String[]{"prefiero", "preferis", "prefiere", "preferimos", "prefieren"});
        PRESENT_IRREGULAR.put("conseguir", new String[]{"consigo", "conseguis", "consigue", "conseguimos", "consiguen"});
        PRESENT_IRREGULAR.put("construir", new String[]{"construyo", "construis", "construye", "construimos", "construyen"});
        PRESENT_IRREGULAR.put("morir", new String[]{"muero", "moris", "muere", "morimos", "mueren"});
        PRESENT_IRREGULAR.put("sentir", new String[]{"siento", "sentis", "siente", "sentimos", "sienten"});
        PRESENT_IRREGULAR.put("repetir", new String[]{"repito", "repetis", "repite", "repetimos", "repiten"});
        PRESENT_IRREGULAR.put("entender", new String[]{"entiendo", "entendes", "entiende", "entendemos", "entienden"});
        PRESENT_IRREGULAR.put("empezar", new String[]{"empiezo", "empezas", "empieza", "empezamos", "empiezan"});
        PRESENT_IRREGULAR.put("oír", new String[]{"oigo", "ois", "oye", "oimos", "oyen"});
        PRESENT_IRREGULAR.put("oir", new String[]{"oigo", "ois", "oye", "oimos", "oyen"});
        PRESENT_IRREGULAR.put("huir", new String[]{"huyo", "huis", "huye", "huimos", "huyen"});

        IMPERATIVE_IRREGULAR.put("ir", "anda");
        IMPERATIVE_IRREGULAR.put("venir", "veni");
        IMPERATIVE_IRREGULAR.put("tener", "tene");
        IMPERATIVE_IRREGULAR.put("hacer", "hace");
        IMPERATIVE_IRREGULAR.put("decir", "deci");
        IMPERATIVE_IRREGULAR.put("poner", "pone");
        IMPERATIVE_IRREGULAR.put("ser", "se");
        IMPERATIVE_IRREGULAR.put("estar", "esta");
        IMPERATIVE_IRREGULAR.put("dar", "da");
        IMPERATIVE_IRREGULAR.put("ver", "mira");
        IMPERATIVE_IRREGULAR.put("saber", "sabe");
        IMPERATIVE_IRREGULAR.put("jugar", "juga");
        IMPERATIVE_IRREGULAR.put("cerrar", "cerra");
        IMPERATIVE_IRREGULAR.put("pensar", "pensa");
        IMPERATIVE_IRREGULAR.put("perder", "perde");
        IMPERATIVE_IRREGULAR.put("volver", "volve");
        IMPERATIVE_IRREGULAR.put("dormir", "dormi");
        IMPERATIVE_IRREGULAR.put("seguir", "segui");
        IMPERATIVE_IRREGULAR.put("pedir", "pedi");
        IMPERATIVE_IRREGULAR.put("repetir", "repeti");
        IMPERATIVE_IRREGULAR.put("sentir", "senti");
        IMPERATIVE_IRREGULAR.put("preferir", "preferi");
        IMPERATIVE_IRREGULAR.put("conseguir", "consegui");
        IMPERATIVE_IRREGULAR.put("construir", "construi");
        IMPERATIVE_IRREGULAR.put("encontrar", "encontra");
        IMPERATIVE_IRREGULAR.put("contar", "conta");
        IMPERATIVE_IRREGULAR.put("entender", "entende");
        IMPERATIVE_IRREGULAR.put("empezar", "empeza");
        IMPERATIVE_IRREGULAR.put("morir", "mori");
        IMPERATIVE_IRREGULAR.put("salir", "sali");
        IMPERATIVE_IRREGULAR.put("conocer", "conoce");

        PRETERITE_IRREGULAR.put("ser", new String[]{"fui", "fuiste", "fue", "fuimos", "fueron"});
        PRETERITE_IRREGULAR.put("ir", new String[]{"fui", "fuiste", "fue", "fuimos", "fueron"});
        PRETERITE_IRREGULAR.put("haber", new String[]{"hube", "hubiste", "hubo", "hubimos", "hubieron"});
        PRETERITE_IRREGULAR.put("tener", new String[]{"tuve", "tuviste", "tuvo", "tuvimos", "tuvieron"});
        PRETERITE_IRREGULAR.put("estar", new String[]{"estuve", "estuviste", "estuvo", "estuvimos", "estuvieron"});
        PRETERITE_IRREGULAR.put("poder", new String[]{"pude", "pudiste", "pudo", "pudimos", "pudieron"});
        PRETERITE_IRREGULAR.put("poner", new String[]{"puse", "pusiste", "puso", "pusimos", "pusieron"});
        PRETERITE_IRREGULAR.put("saber", new String[]{"supe", "supiste", "supo", "supimos", "supieron"});
        PRETERITE_IRREGULAR.put("hacer", new String[]{"hice", "hiciste", "hizo", "hicimos", "hicieron"});
        PRETERITE_IRREGULAR.put("querer", new String[]{"quise", "quisiste", "quiso", "quisimos", "quisieron"});
        PRETERITE_IRREGULAR.put("venir", new String[]{"vine", "viniste", "vino", "vinimos", "vinieron"});
        PRETERITE_IRREGULAR.put("decir", new String[]{"dije", "dijiste", "dijo", "dijimos", "dijeron"});
        PRETERITE_IRREGULAR.put("traer", new String[]{"traje", "trajiste", "trajo", "trajimos", "trajeron"});
        PRETERITE_IRREGULAR.put("dar", new String[]{"di", "diste", "dio", "dimos", "dieron"});
        PRETERITE_IRREGULAR.put("ver", new String[]{"vi", "viste", "vio", "vimos", "vieron"});
        PRETERITE_IRREGULAR.put("andar", new String[]{"anduve", "anduviste", "anduvo", "anduvimos", "anduvieron"});

        SUBJUNCTIVE_STEM.put("saber", "sep");
        SUBJUNCTIVE_STEM.put("haber", "hay");
        SUBJUNCTIVE_STEM.put("ir", "vay");
        SUBJUNCTIVE_STEM.put("ser", "se");
        SUBJUNCTIVE_STEM.put("estar", "est");
        SUBJUNCTIVE_STEM.put("dar", "d");
        SUBJUNCTIVE_STEM.put("ver", "ve");
        SUBJUNCTIVE_STEM.put("oír", "oig");
        SUBJUNCTIVE_STEM.put("oir", "oig");
        SUBJUNCTIVE_STEM.put("huir", "huy");
        SUBJUNCTIVE_STEM.put("tener", "teng");
        SUBJUNCTIVE_STEM.put("hacer", "hag");
        SUBJUNCTIVE_STEM.put("poder", "pued");
        SUBJUNCTIVE_STEM.put("querer", "quier");
        SUBJUNCTIVE_STEM.put("decir", "dig");
        SUBJUNCTIVE_STEM.put("venir", "veng");
        SUBJUNCTIVE_STEM.put("poner", "pong");
        SUBJUNCTIVE_STEM.put("traer", "traig");
        SUBJUNCTIVE_STEM.put("salir", "salg");
        SUBJUNCTIVE_STEM.put("conocer", "conozc");
        SUBJUNCTIVE_STEM.put("construir", "construy");
        SUBJUNCTIVE_STEM.put("jugar", "jueg");
        SUBJUNCTIVE_STEM.put("llegar", "lleg");
        SUBJUNCTIVE_STEM.put("empezar", "empiec");
        SUBJUNCTIVE_STEM.put("pensar", "piens");
        SUBJUNCTIVE_STEM.put("volver", "vuelv");
        SUBJUNCTIVE_STEM.put("dormir", "duerm");
        SUBJUNCTIVE_STEM.put("pedir", "pid");
        SUBJUNCTIVE_STEM.put("sentir", "sient");
        SUBJUNCTIVE_STEM.put("seguir", "sig");
        SUBJUNCTIVE_STEM.put("conseguir", "consig");
        SUBJUNCTIVE_STEM.put("preferir", "prefier");
        SUBJUNCTIVE_STEM.put("morir", "muer");
        SUBJUNCTIVE_STEM.put("perder", "pierd");
        SUBJUNCTIVE_STEM.put("entender", "entiend");

        FUTURE_STEM.put("tener", "tendr");
        FUTURE_STEM.put("poder", "podr");
        FUTURE_STEM.put("querer", "querr");
        FUTURE_STEM.put("saber", "sabr");
        FUTURE_STEM.put("hacer", "har");
        FUTURE_STEM.put("decir", "dir");
        FUTURE_STEM.put("venir", "vendr");
        FUTURE_STEM.put("salir", "saldr");
        FUTURE_STEM.put("poner", "pondr");
        FUTURE_STEM.put("haber", "habr");
        FUTURE_STEM.put("caber", "cabr");
        FUTURE_STEM.put("valer", "valdr");

        IMPERFECT_IRREGULAR.put("ser", new String[]{"era", "eras", "era", "eramos", "eran"});
        IMPERFECT_IRREGULAR.put("ir", new String[]{"iba", "ibas", "iba", "ibamos", "iban"});
        IMPERFECT_IRREGULAR.put("ver", new String[]{"veia", "veias", "veia", "veiamos", "veian"});
    }

    private Spanish() {
    }

    /**
     * Lowercase, accents and punctuation removed, chat spellings repaired:
     * abbreviations expanded ("q" -> "que", "xq" -> "porque"), leet digits
     * turned into letters in mixed tokens, and stretched letters collapsed
     * ("holaaa" -> "hola", "nooo" -> "no"). Without this, half of what players
     * type in a hurry never matches the lexicon.
     */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String decomposed = stripNonAscii(Normalizer.normalize(text, Normalizer.Form.NFD));
        String cleaned = decomposed.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .trim()
                .replaceAll(" +", " ");
        if (cleaned.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(cleaned.length() + 8);
        for (String token : cleaned.split(" ")) {
            if (token.isEmpty()) {
                continue;
            }
            String fixed = repair(token);
            String expanded = ABBREVIATIONS.get(fixed);
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(expanded != null ? expanded : fixed);
        }
        return out.toString();
    }

    /** Drops the combining marks left by NFD, keeping every plain ASCII letter. */
    private static String stripNonAscii(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 128) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Leet digits and stretched letters, applied to one token. */
    private static String repair(String token) {
        StringBuilder builder = new StringBuilder(token.length());
        boolean hasLetter = token.chars().anyMatch(Character::isLetter);
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            Character mapped = hasLetter ? LEET.get(c) : null;
            builder.append(mapped == null ? c : mapped);
        }
        // "holaaa" -> "hola", "nooo" -> "no"; two equal letters ("perro",
        // "calle") are legitimate and stay.
        return collapse(builder.toString());
    }

    /** Shrinks a run of three or more equal letters to one. */
    private static String collapse(String token) {
        StringBuilder out = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            int run = 1;
            while (i + run < token.length() && token.charAt(i + run) == c) {
                run++;
            }
            out.append(c);
            if (run == 2) {
                out.append(c);
            }
            i += run - 1;
        }
        return out.toString();
    }

    /** Accents removed but original casing kept, for printing clean text. */
    public static String plain(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }

    public static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        for (String token : normalize(text).split("[^\\p{L}\\p{N}]+")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    public static String singular(String word) {
        if (word.length() <= 3) {
            return word;
        }
        if (word.endsWith("ces")) {
            return word.substring(0, word.length() - 3) + "z";
        }
        if (word.endsWith("es") && "sxz".indexOf(word.charAt(word.length() - 3)) >= 0) {
            return word.substring(0, word.length() - 2);
        }
        if (word.endsWith("s")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    public static String plural(String word) {
        if (word.isEmpty()) {
            return word;
        }
        char last = word.charAt(word.length() - 1);
        if ("aeiou".indexOf(last) >= 0) {
            return word + "s";
        }
        if (last == 'z') {
            return word.substring(0, word.length() - 1) + "ces";
        }
        return word + "es";
    }

    public static String conjugate(String infinitive, Tense tense, Person person) {
        String verb = normalize(infinitive);
        if (verb.endsWith("se")) {
            verb = verb.substring(0, verb.length() - 2);
        }
        String stem = verb.length() > 2 ? verb.substring(0, verb.length() - 2) : verb;
        String ending = verb.length() > 2 ? verb.substring(verb.length() - 2) : "";
        int index = switch (person) {
            case YO -> 0;
            case TU -> 1;
            case EL -> 2;
            case NOSOTROS -> 3;
            case ELLOS -> 4;
        };
        switch (tense) {
            case INFINITIVO:
                return verb;
            case GERUNDIO:
                return switch (verb) {
                    case "ir" -> "yendo";
                    case "leer" -> "leyendo";
                    case "oir", "oír" -> "oyendo";
                    default -> ending.equals("ar") ? stem + "ando"
                            : stem + (verb.equals("dormir") || verb.equals("morir") ? "iendo" : "iendo");
                };
            case PARTICIPIO:
                return ending.equals("ar") ? stem + "ado" : stem + "ido";
            case IMPERATIVO: {
                String irregular = IMPERATIVE_IRREGULAR.get(verb);
                if (irregular != null) {
                    return person == Person.NOSOTROS ? stem + imperativeEnding(ending, true) : irregular;
                }
                return stem + imperativeEnding(ending, person == Person.NOSOTROS);
            }
            case PRESENTE: {
                String[] irregular = PRESENT_IRREGULAR.get(verb);
                if (irregular != null) {
                    return irregular[index];
                }
                return stem + switch (ending) {
                    case "ar" -> new String[]{"o", "as", "a", "amos", "an"}[index];
                    case "er" -> new String[]{"o", "es", "e", "emos", "en"}[index];
                    default -> new String[]{"o", "es", "e", "imos", "en"}[index];
                };
            }
            case PRETERITO: {
                String[] irregular = PRETERITE_IRREGULAR.get(verb);
                if (irregular != null) {
                    return irregular[index];
                }
                return stem + switch (ending) {
                    case "ar" -> new String[]{"e", "aste", "o", "amos", "aron"}[index];
                    case "er", "ir" -> new String[]{"i", "iste", "io", "imos", "ieron"}[index];
                    default -> "";
                };
            }
            case IMPERFECTO: {
                String[] irregular = IMPERFECT_IRREGULAR.get(verb);
                if (irregular != null) {
                    return irregular[index];
                }
                return ending.equals("ar")
                        ? stem + new String[]{"aba", "abas", "aba", "abamos", "aban"}[index]
                        : stem + new String[]{"ia", "ias", "ia", "iamos", "ian"}[index];
            }
            case FUTURO: {
                String base = FUTURE_STEM.getOrDefault(verb, verb);
                return base + new String[]{"e", "as", "a", "emos", "an"}[index];
            }
            case CONDICIONAL: {
                String base = FUTURE_STEM.getOrDefault(verb, verb);
                return base + new String[]{"ia", "ias", "ia", "iamos", "ian"}[index];
            }
            case SUBJUNTIVO: {
                String subjunctive = SUBJUNCTIVE_STEM.get(verb);
                if (subjunctive == null) {
                    String yo = PRESENT_IRREGULAR.containsKey(verb)
                            ? PRESENT_IRREGULAR.get(verb)[0] : stem + "o";
                    subjunctive = yo.endsWith("o") ? yo.substring(0, yo.length() - 1) : yo;
                }
                return ending.equals("ar")
                        ? subjunctive + new String[]{"e", "es", "e", "emos", "en"}[index]
                        : subjunctive + new String[]{"a", "as", "a", "amos", "an"}[index];
            }
            default:
                return verb;
        }
    }

    private static String imperativeEnding(String ending, boolean plural) {
        if (plural) {
            return ending.equals("ar") ? "emos" : "amos";
        }
        return switch (ending) {
            case "ar" -> "a";
            case "er" -> "e";
            default -> "i";
        };
    }

    public static String capitalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
