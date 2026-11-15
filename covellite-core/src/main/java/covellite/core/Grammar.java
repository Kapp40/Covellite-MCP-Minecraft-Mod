package covellite.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Spanish morphology Cove needs to actually talk about itself.
 *
 * <p>Small and deliberate: infinitive to gerund ("minar" -&gt; "minando") and
 * infinitive to first person present ("minar" -&gt; "mino"). Irregulars are listed
 * explicitly because guessing them is how a canned-phrase bot sounds.</p>
 */
public final class Grammar {

    private Grammar() {
    }

    /** Verbs whose gerund changes the stem. */
    private static final Map<String, String> GERUND_IRREGULAR = new HashMap<>();

    /** First person present, when it is not the regular ending. */
    private static final Map<String, String> FIRST_IRREGULAR = new HashMap<>();

    /** -ar and -er verbs with a stressed e that becomes ie. */
    private static final Set<String> E_TO_IE = Set.of(
            "pensar", "empezar", "comenzar", "cerrar", "perder", "querer", "entender",
            "encender", "atender", "sentar", "despertar", "despertarse", "calentar",
            "recomendar", "regar", "negar", "confesar", "merendar", "apretar", "fregar",
            "acertar", "advertir", "convertir", "divertir", "mentir", "sentir", "preferir");

    /** Stressed o that becomes ue. */
    private static final Set<String> O_TO_UE = Set.of(
            "volver", "devolver", "envolver", "revolver", "encontrar", "contar", "recordar",
            "acostar", "colgar", "mostrar", "soñar", "sonar", "costar", "probar", "aprobar",
            "rogar", "doler", "mover", "llover", "poder", "dormir", "morir", "almorzar",
            "esforzar", "forzar", "tostar", "volar", "soltar", "resolver");

    /** -ir verbs whose e becomes i in the first person. */
    private static final Set<String> E_TO_I = Set.of(
            "pedir", "seguir", "servir", "repetir", "conseguir", "elegir", "corregir",
            "medir", "vestir", "reír", "sonreír", "freír", "decir", "despedir", "impedir",
            "competir", "derretir", "gemir", "regir", "teñir", "concebir", "expedir");

    static {
        GERUND_IRREGULAR.put("ir", "yendo");
        GERUND_IRREGULAR.put("leer", "leyendo");
        GERUND_IRREGULAR.put("creer", "creyendo");
        GERUND_IRREGULAR.put("poseer", "poseyendo");
        GERUND_IRREGULAR.put("caer", "cayendo");
        GERUND_IRREGULAR.put("traer", "trayendo");
        GERUND_IRREGULAR.put("oír", "oyendo");
        GERUND_IRREGULAR.put("reír", "riendo");
        GERUND_IRREGULAR.put("sonreír", "sonriendo");
        GERUND_IRREGULAR.put("freír", "friendo");
        GERUND_IRREGULAR.put("huir", "huyendo");
        GERUND_IRREGULAR.put("construir", "construyendo");
        GERUND_IRREGULAR.put("destruir", "destruyendo");
        GERUND_IRREGULAR.put("incluir", "incluyendo");
        GERUND_IRREGULAR.put("dormir", "durmiendo");
        GERUND_IRREGULAR.put("morir", "muriendo");
        GERUND_IRREGULAR.put("poder", "pudiendo");
        GERUND_IRREGULAR.put("pedir", "pidiendo");
        GERUND_IRREGULAR.put("seguir", "siguiendo");
        GERUND_IRREGULAR.put("conseguir", "consiguiendo");
        GERUND_IRREGULAR.put("servir", "sirviendo");
        GERUND_IRREGULAR.put("repetir", "repitiendo");
        GERUND_IRREGULAR.put("preferir", "prefiriendo");
        GERUND_IRREGULAR.put("sentir", "sintiendo");
        GERUND_IRREGULAR.put("mentir", "mintiendo");
        GERUND_IRREGULAR.put("decir", "diciendo");
        GERUND_IRREGULAR.put("venir", "viniendo");
        GERUND_IRREGULAR.put("tener", "teniendo");
        GERUND_IRREGULAR.put("venirse", "viniéndose");
        GERUND_IRREGULAR.put("jugar", "jugando");
        GERUND_IRREGULAR.put("ver", "viendo");
        GERUND_IRREGULAR.put("ir", "yendo");

        FIRST_IRREGULAR.put("ser", "soy");
        FIRST_IRREGULAR.put("estar", "estoy");
        FIRST_IRREGULAR.put("ir", "voy");
        FIRST_IRREGULAR.put("haber", "he");
        FIRST_IRREGULAR.put("tener", "tengo");
        FIRST_IRREGULAR.put("hacer", "hago");
        FIRST_IRREGULAR.put("poner", "pongo");
        FIRST_IRREGULAR.put("salir", "salgo");
        FIRST_IRREGULAR.put("traer", "traigo");
        FIRST_IRREGULAR.put("caer", "caigo");
        FIRST_IRREGULAR.put("oír", "oigo");
        FIRST_IRREGULAR.put("dar", "doy");
        FIRST_IRREGULAR.put("ver", "veo");
        FIRST_IRREGULAR.put("saber", "sé");
        FIRST_IRREGULAR.put("decir", "digo");
        FIRST_IRREGULAR.put("venir", "vengo");
        FIRST_IRREGULAR.put("conocer", "conozco");
        FIRST_IRREGULAR.put("parecer", "parezco");
        FIRST_IRREGULAR.put("conducir", "conduzco");
        FIRST_IRREGULAR.put("traducir", "traduzco");
        FIRST_IRREGULAR.put("jugar", "juego");
        FIRST_IRREGULAR.put("querer", "quiero");
        FIRST_IRREGULAR.put("poder", "puedo");
        FIRST_IRREGULAR.put("dormir", "duermo");
        FIRST_IRREGULAR.put("morir", "muero");
        FIRST_IRREGULAR.put("volver", "vuelvo");
        FIRST_IRREGULAR.put("encontrar", "encuentro");
        FIRST_IRREGULAR.put("seguir", "sigo");
        FIRST_IRREGULAR.put("conseguir", "consigo");
        FIRST_IRREGULAR.put("pedir", "pido");
        FIRST_IRREGULAR.put("servir", "sirvo");
        FIRST_IRREGULAR.put("repetir", "repito");
        FIRST_IRREGULAR.put("preferir", "prefiero");
        FIRST_IRREGULAR.put("sentir", "siento");
        FIRST_IRREGULAR.put("mentir", "miento");
        FIRST_IRREGULAR.put("pensar", "pienso");
        FIRST_IRREGULAR.put("empezar", "empiezo");
        FIRST_IRREGULAR.put("comenzar", "comienzo");
        FIRST_IRREGULAR.put("entender", "entiendo");
        FIRST_IRREGULAR.put("perder", "pierdo");
        FIRST_IRREGULAR.put("reír", "río");
        FIRST_IRREGULAR.put("sonreír", "sonrío");
        FIRST_IRREGULAR.put("freír", "frío");
        FIRST_IRREGULAR.put("construir", "construyo");
        FIRST_IRREGULAR.put("huir", "huyo");
        FIRST_IRREGULAR.put("elegir", "elijo");
        FIRST_IRREGULAR.put("corregir", "corrijo");
        FIRST_IRREGULAR.put("medir", "mido");
    }

    /** "minar" -&gt; "minando"; an unknown word comes back unchanged. */
    public static String gerund(String infinitive) {
        String verb = clean(infinitive);
        if (verb.isEmpty()) {
            return "";
        }
        String known = GERUND_IRREGULAR.get(verb);
        if (known != null) {
            return known;
        }
        if (verb.endsWith("ar")) {
            return verb.substring(0, verb.length() - 2) + "ando";
        }
        if (verb.endsWith("er") || verb.endsWith("ir")) {
            return verb.substring(0, verb.length() - 2) + "iendo";
        }
        return verb;
    }

    /** "minar" -&gt; "mino"; "talar" -&gt; "talo"; an unknown word comes back unchanged. */
    public static String firstPerson(String infinitive) {
        String verb = clean(infinitive);
        if (verb.isEmpty()) {
            return "";
        }
        String known = FIRST_IRREGULAR.get(verb);
        if (known != null) {
            return known;
        }
        String stem;
        String ending;
        if (verb.endsWith("ar")) {
            stem = verb.substring(0, verb.length() - 2);
            ending = "o";
        } else if (verb.endsWith("er") || verb.endsWith("ir")) {
            stem = verb.substring(0, verb.length() - 2);
            ending = "o";
        } else {
            return verb;
        }
        String changed = stemChange(verb, stem);
        return changed + ending;
    }

    /** "T45_" + "talar" -&gt; "T45_ tala": third person, for reporting on somebody else. */
    public static String thirdPerson(String infinitive) {
        String verb = clean(infinitive);
        if (verb.isEmpty()) {
            return "";
        }
        String stem;
        if (verb.endsWith("ar")) {
            stem = stemChange(verb, verb.substring(0, verb.length() - 2));
            return stem + "a";
        }
        if (verb.endsWith("er")) {
            stem = stemChange(verb, verb.substring(0, verb.length() - 2));
            return stem + "e";
        }
        if (verb.endsWith("ir")) {
            stem = stemChange(verb, verb.substring(0, verb.length() - 2));
            return stem + "e";
        }
        return verb;
    }

    /** True when the gerund reads like a real Spanish gerund. */
    public static boolean isGerund(String word) {
        return word != null && word.endsWith("ndo") && word.length() > 3;
    }

    private static String stemChange(String verb, String stem) {
        if (stem.isEmpty()) {
            return stem;
        }
        if (O_TO_UE.contains(verb)) {
            int at = lastVowel(stem, 'o');
            if (at >= 0) {
                return stem.substring(0, at) + "ue" + stem.substring(at + 1);
            }
        }
        if (E_TO_IE.contains(verb)) {
            int at = lastVowel(stem, 'e');
            if (at >= 0) {
                return stem.substring(0, at) + "ie" + stem.substring(at + 1);
            }
        }
        if (E_TO_I.contains(verb)) {
            int at = lastVowel(stem, 'e');
            if (at >= 0) {
                return stem.substring(0, at) + "i" + stem.substring(at + 1);
            }
        }
        return stem;
    }

    /** Position of the stem's stressed vowel, when it is the one that changes. */
    private static int lastVowel(String stem, char vowel) {
        for (int i = stem.length() - 1; i >= 0; i--) {
            char c = stem.charAt(i);
            if (isVowel(c)) {
                return c == vowel ? i : -1;
            }
        }
        return -1;
    }

    private static boolean isVowel(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u';
    }

    private static String clean(String infinitive) {
        if (infinitive == null) {
            return "";
        }
        String verb = infinitive.trim().toLowerCase(java.util.Locale.ROOT);
        if (verb.startsWith("to ")) {
            verb = verb.substring(3);
        }
        return verb;
    }
}
