package covellite.agent.lang;

import covellite.agent.social.SocialState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads a player message without a language model.
 *
 * <p>It is a real analysis, not a phrase table: it tokenizes, classifies the
 * sentence type, resolves entities through the lexicon, checks negation and
 * second person, matches the message against the question Cove is waiting on,
 * and flags an answer that contradicts the known world as a joke instead of
 * taking it literally.</p>
 */
public final class Parser {

    /** What the player did with words. */
    public enum Act {
        ORDEN, PREGUNTA_A_COVE, RESPUESTA_SI, RESPUESTA_NO, RESPUESTA_DATO,
        BROMA, CORRECCION, SALUDO, CHARLA, AMBIGUA
    }

    /** One analysed message. */
    public record Reading(
            Act act,
            String action,
            String actionHint,
            List<String> items,
            List<String> places,
            List<String> mobs,
            String direction,
            Integer number,
            boolean negated,
            boolean addressed,
            boolean incongruent,
            boolean answersPending,
            String verb,
            String text) {

        public boolean order() {
            return this.act == Act.ORDEN;
        }

        public boolean joke() {
            return this.act == Act.BROMA;
        }
    }

    private static final Set<String> QUESTION_WORDS = Set.of(
            "que", "como", "donde", "cuando", "porque", "quien", "cual", "cuanto",
            "adonde", "cuantos", "cuanta", "cuantas");

    private static final Set<String> LOCATIVE_VERBS = Set.of(
            "esta", "estan", "queda", "quedan", "es", "son", "vivimos", "estamos");

    private static final Set<String> CORRECTION_MARKERS = Set.of(
            "no era", "te dije", "te habia dicho", "estas equivocado", "equivocado",
            "eso no", "mal", "otra vez", "no es eso", "nada que ver", "al reves");

    private static final Set<String> LAUGHTER = Set.of("jaja", "jeje", "jajaja", "jsjs", "lol");

    private static final Set<String> SECOND_PERSON = Set.of(
            "vos", "tenes", "podes", "queres", "sabes", "estas", "sos", "te", "ti", "tu", "tus",
            "haces", "vas", "venis", "mira", "miravos");

    private final Lexicon lexicon;

    public Parser() {
        this.lexicon = Lexicon.shared();
    }

    public Reading parse(String raw, WorldFacts facts, SocialState.PendingQuestion pending) {
        String text = raw == null ? "" : raw.trim();
        WorldFacts world = facts == null ? WorldFacts.empty() : facts;
        if (text.isEmpty()) {
            return new Reading(Act.AMBIGUA, "", "", List.of(), List.of(), List.of(), "",
                    null, false, false, false, false, "", text);
        }
        List<String> tokens = Spanish.tokens(text);
        String normalized = Spanish.normalize(text);

        Set<String> items = new LinkedHashSet<>();
        Set<String> places = new LinkedHashSet<>();
        Set<String> mobs = new LinkedHashSet<>();
        Set<String> unknown = new LinkedHashSet<>();
        String direction = "";
        Integer number = numberIn(tokens);
        // A verb inflection wins over a homonym noun: "mina" in "mina hierro"
        // is the order, not the place.
        Lexicon.Entry verb = this.lexicon.verbIn(tokens);

        for (String token : tokens) {
            Lexicon.Entry entry = this.lexicon.lookup(token);
            if (entry == null) {
                if (token.length() >= 4 && !isStopWord(token)) {
                    unknown.add(token);
                }
                continue;
            }
            switch (entry.category()) {
                case ITEM -> items.add(entry.canonical());
                case PLACE -> places.add(entry.canonical());
                case MOB -> mobs.add(entry.canonical());
                case DIR -> direction = entry.canonical();
                case VERB -> {
                    if (verb == null) {
                        verb = entry;
                    }
                }
                default -> {
                }
            }
        }

        boolean greeting = tokens.stream().anyMatch(token -> {
            Lexicon.Entry entry = this.lexicon.lookup(token);
            return entry != null && entry.category() == Lexicon.Category.GREET;
        });
        boolean laughter = tokens.stream().anyMatch(LAUGHTER::contains);
        boolean negation = tokens.contains("no") || tokens.contains("nunca") || tokens.contains("jamas");
        boolean question = text.contains("?") || text.contains("¿")
                || (!tokens.isEmpty() && QUESTION_WORDS.contains(tokens.get(0)))
                || normalized.contains("tenes idea") || normalized.contains("sabes donde");
        boolean addressed = question || tokens.stream().anyMatch(SECOND_PERSON::contains)
                || tokens.contains("cove") || tokens.stream().anyMatch(token -> token.equals("che"));
        boolean correction = CORRECTION_MARKERS.stream().anyMatch(normalized::contains);

        String action = verb == null ? "" : verb.action();
        String hint = verb == null ? "" : verb.hint();
        boolean imperative = verb != null && looksImperative(tokens, verb.canonical())
                || normalized.contains("tenes que") || normalized.contains("podes ")
                || normalized.contains("quiero que") || normalized.contains("ayudame");

        // An answer that denies or moves a place Cove knows is a joke, not a fact.
        boolean incongruent = pending != null && !unknown.isEmpty()
                && tokens.stream().anyMatch(LOCATIVE_VERBS::contains)
                && (places.isEmpty() || pending.topic().toLowerCase(Locale.ROOT).contains("ubic"))
                && !negation;

        Act act;
        boolean answers = false;
        if (correction) {
            act = Act.CORRECCION;
            answers = pending != null;
        } else if (incongruent && pending != null) {
            act = Act.BROMA;
            answers = true;
        } else if (laughter && pending != null && !question && !imperative) {
            act = Act.BROMA;
            answers = true;
        } else if (question) {
            act = Act.PREGUNTA_A_COVE;
        } else if (imperative || (verb != null && !verb.action().isBlank() && !addressed && !negation)) {
            act = Act.ORDEN;
        } else if (greeting && tokens.size() <= 5) {
            act = Act.SALUDO;
        } else if (pending != null) {
            if (tokens.contains("si") || tokens.contains("dale") || tokens.contains("claro")
                    || tokens.contains("obvio") || tokens.contains("joya")) {
                act = Act.RESPUESTA_SI;
                answers = true;
            } else if (negation) {
                act = Act.RESPUESTA_NO;
                answers = true;
            } else if (!direction.isEmpty() || number != null || !places.isEmpty()
                    || !items.isEmpty() || !mobs.isEmpty()) {
                act = Act.RESPUESTA_DATO;
                answers = true;
            } else if (laughter) {
                act = Act.BROMA;
                answers = true;
            } else {
                act = Act.RESPUESTA_DATO;
                answers = pending.topic().toLowerCase(Locale.ROOT).contains("ubic")
                        || !pending.topic().isBlank();
            }
        } else if (laughter) {
            act = Act.BROMA;
        } else {
            act = Act.CHARLA;
        }

        double confidence = act == Act.AMBIGUA ? 0.2D : (verb != null || question ? 0.8D : 0.55D);
        return new Reading(act, action, hint, List.copyOf(items), List.copyOf(places),
                List.copyOf(mobs), direction, number, negation, addressed, incongruent, answers,
                verb == null ? "" : verb.canonical(), text);
    }

    private static boolean looksImperative(List<String> tokens, String infinitive) {
        String imperative = Spanish.conjugate(infinitive, Spanish.Tense.IMPERATIVO,
                Spanish.Person.TU);
        return tokens.contains(imperative) || tokens.contains(infinitive)
                || tokens.contains(Spanish.conjugate(infinitive, Spanish.Tense.IMPERATIVO,
                Spanish.Person.NOSOTROS));
    }

    private static boolean isStopWord(String token) {
        return token.length() < 4 || Set.of("para", "pero", "este", "esto", "esa", "ese", "porque",
                "cuando", "donde", "todo", "muy", "mas", "menos", "algo", "nada", "cosa").contains(token);
    }

    private static Integer numberIn(List<String> tokens) {
        for (String token : tokens) {
            if (token.matches("\\d{1,4}")) {
                return Integer.parseInt(token);
            }
        }
        for (String token : tokens) {
            Integer value = switch (token) {
                case "un", "una", "uno" -> 1;
                case "dos" -> 2;
                case "tres" -> 3;
                case "cuatro" -> 4;
                case "cinco" -> 5;
                case "diez" -> 10;
                default -> null;
            };
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
