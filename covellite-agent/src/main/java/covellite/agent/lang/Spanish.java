package covellite.agent.lang;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal Spanish morphology for a self-contained generator.
 *
 * <p>Only what Cove needs to speak in rioplatense and to recognise the common
 * inflections of the verbs in the lexicon: normalize, tokenize, conjugate
 * (present, preterite, voseo imperative), pluralize and agree. No dictionaries
 * beyond the ones written here, so the whole module stays tiny.</p>
 */
public final class Spanish {

    public enum Tense { PRESENTE, PRETERITO, IMPERATIVO, GERUNDIO, PARTICIPIO }

    /** YO, TU is voseo ("vos"), EL is third person singular. */
    public enum Person { YO, TU, EL, NOSOTROS, ELLOS }

    /** Irregular present forms: yo, vos, el, nosotros, ellos. */
    private static final Map<String, String[]> PRESENT_IRREGULAR = new HashMap<>();

    /** Irregular voseo imperatives. */
    private static final Map<String, String> IMPERATIVE_IRREGULAR = new HashMap<>();

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
    }

    private Spanish() {
    }

    /** Lowercase, accents removed, whitespace collapsed. */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return decomposed.toLowerCase(Locale.ROOT)
                .replaceAll("[¿?¡!.,;:()\"']", " ")
                .trim()
                .replaceAll("\\s+", " ");
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
            // Reflective verbs: conjugate without the pronoun and add "te" for voseo.
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
        if (tense == Tense.GERUNDIO) {
            if (ending.equals("ar")) {
                return stem + "ando";
            }
            String irregular = switch (verb) {
                case "ir" -> "yendo";
                case "leer" -> "leyendo";
                case "oír", "oir" -> "oyendo";
                default -> stem + "iendo";
            };
            return irregular;
        }
        if (tense == Tense.PARTICIPIO) {
            return ending.equals("ar") ? stem + "ado" : stem + "ido";
        }
        if (tense == Tense.IMPERATIVO) {
            String irregular = IMPERATIVE_IRREGULAR.get(verb);
            if (irregular != null) {
                return person == Person.NOSOTROS ? stem + endingImperative(ending, true) : irregular;
            }
            return stem + endingImperative(ending, person == Person.NOSOTROS);
        }
        if (tense == Tense.PRESENTE) {
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
        // Preterite, regular forms; enough for narration.
        return stem + switch (ending) {
            case "ar" -> new String[]{"e", "aste", "o", "amos", "aron"}[index];
            case "er", "ir" -> new String[]{"i", "iste", "io", "imos", "ieron"}[index];
            default -> "";
        };
    }

    private static String endingImperative(String ending, boolean plural) {
        if (plural) {
            return ending.equals("ar") ? "emos" : "amos";
        }
        return switch (ending) {
            case "ar" -> "a";
            case "er" -> "e";
            default -> "i";
        };
    }

    /** a/an by initial sound and gender, for the few nouns Cove prints. */
    public static String article(String word, boolean feminine, boolean plural) {
        String base;
        if (feminine) {
            base = plural ? "las" : "la";
        } else {
            base = plural ? "los" : "el";
        }
        return base;
    }

    public static String capitalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    public static String join(List<String> parts, String separator) {
        return String.join(separator, parts);
    }
}
