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
 *
 * <p>Classification is compositional: a preference needs a preference marker and
 * a first person, a feeling needs a feeling word and a first person, an order
 * needs an imperative form (voseo included) or an infinitive after "a"/"que".
 * A bare infinitive inside a statement ("me gusta comer pan") is not an
 * order.</p>
 */
public final class Parser {

    /** What the player did with words. */
    public enum Act {
        ORDEN, PREGUNTA_A_COVE, PREGUNTA_SOBRE_COVE, RESPUESTA_SI, RESPUESTA_NO,
        RESPUESTA_DATO, BROMA, CORRECCION, SALUDO, PREFERENCIA, SENTIMIENTO, ELOGIO,
        INSULTO, PLAN, CHARLA, DUDOSA, AMBIGUA
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
            String feeling,
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

    private static final Set<String> YES_WORDS = Set.of(
            "si", "dale", "claro", "obvio", "joya", "ok", "okey", "correcto", "exacto");

    private static final Set<String> FIRST_PERSON = Set.of(
            "estoy", "tengo", "siento", "ando", "me", "mi", "quiero", "prefiero", "odio",
            "estamos", "tenemos");

    private static final Set<String> ABOUT_COVE = Set.of(
            "como estas", "como andas", "que opinas", "que pensas", "quien sos",
            "que haces", "estas bien", "que te parece", "como te va", "que sabes");

    private static final Set<String> PLAN_MARKERS = Set.of(
            "vamos a", "tenemos que", "hay que", "deberiamos", "planeemos", "podriamos");

    private final Lexicon lexicon;

    public Parser() {
        this.lexicon = Lexicon.shared();
    }

    public Reading parse(String raw, WorldFacts facts, SocialState.PendingQuestion pending) {
        String text = raw == null ? "" : raw.trim();
        WorldFacts world = facts == null ? WorldFacts.empty() : facts;
        if (text.isEmpty()) {
            return empty("");
        }
        List<String> tokens = Spanish.tokens(text);
        String normalized = Spanish.normalize(text);

        Set<String> items = new LinkedHashSet<>();
        Set<String> places = new LinkedHashSet<>();
        Set<String> mobs = new LinkedHashSet<>();
        String direction = "";
        String feeling = "";
        boolean preference = false;
        boolean praise = false;
        boolean insult = false;
        boolean absurd = false;
        Integer number = numberIn(tokens);
        Lexicon.Entry verb = this.lexicon.verbIn(tokens);
        boolean firstPerson = tokens.stream().anyMatch(FIRST_PERSON::contains);

        for (String token : tokens) {
            Lexicon.Entry entry = this.lexicon.lookup(token);
            if (entry == null) {
                continue;
            }
            switch (entry.category()) {
                case ITEM -> items.add(entry.canonical());
                case PLACE -> places.add(entry.canonical());
                case MOB -> mobs.add(entry.canonical());
                case DIR -> direction = entry.canonical();
                case FEELING -> feeling = entry.canonical();
                case ABSURD -> absurd = true;
                case VERB -> {
                    if (verb == null) {
                        verb = entry;
                    }
                }
                case WORD -> {
                    switch (entry.canonical()) {
                        case "preferencia" -> preference = true;
                        case "elogio" -> praise = true;
                        case "insulto" -> insult = true;
                        default -> {
                        }
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
        boolean negation = tokens.contains("no") || tokens.contains("nunca")
                || tokens.contains("jamas") || normalized.startsWith("no ");
        boolean question = text.contains("?") || text.contains("¿")
                || (!tokens.isEmpty() && QUESTION_WORDS.contains(tokens.get(0)));
        boolean addressed = question || tokens.stream().anyMatch(SECOND_PERSON::contains)
                || tokens.contains("cove") || tokens.contains("che");
        boolean correction = CORRECTION_MARKERS.stream().anyMatch(normalized::contains);
        boolean aboutCove = ABOUT_COVE.stream().anyMatch(normalized::contains);
        boolean plan = PLAN_MARKERS.stream().anyMatch(normalized::contains);

        // Multiword expressions carry tone that single tokens cannot: "ni en
        // pedo" is a no, "de una" is a yes, "me copa" is a preference.
        boolean yesPhrase = false;
        boolean unsure = false;
        Lexicon.Entry phrase = this.lexicon.phraseIn(normalized);
        if (phrase != null) {
            switch (phrase.canonical()) {
                case "greet" -> greeting = true;
                case "insulto" -> insult = true;
                case "elogio" -> praise = true;
                case "preferencia" -> preference = true;
                case "plan" -> plan = true;
                case "yes" -> yesPhrase = true;
                case "no" -> negation = true;
                case "jaja" -> laughter = true;
                case "duda" -> unsure = true;
                default -> {
                }
            }
        }

        String action = verb == null ? "" : verb.action();
        String hint = verb == null ? "" : verb.hint();
        boolean imperative = verb != null && looksImperative(tokens, verb.canonical())
                || tokens.stream().anyMatch(this.lexicon::isImperative)
                || normalized.contains("tenes que") || normalized.contains("podes ")
                || normalized.contains("quiero que") || normalized.contains("ayudame");

        // An answer that names a place Cove cannot know, or a place from another
        // world, contradicts the world: that is a joke, not a fact.
        boolean locative = tokens.stream().anyMatch(LOCATIVE_VERBS::contains);
        boolean unknownCap = capitalizedUnknown(text, tokens);
        boolean incongruent = pending != null && !negation && locative
                && (absurd || unknownCap)
                && (places.isEmpty() || pending.topic().toLowerCase(Locale.ROOT).contains("ubic")
                || absurd);

        boolean answers = false;
        Act act;
        if (correction) {
            act = Act.CORRECCION;
            answers = pending != null;
        } else if (incongruent) {
            act = Act.BROMA;
            answers = true;
        } else if (laughter) {
            act = Act.BROMA;
            answers = pending != null;
        } else if (aboutCove) {
            act = Act.PREGUNTA_SOBRE_COVE;
        } else if (insult) {
            act = Act.INSULTO;
        } else if (praise) {
            act = Act.ELOGIO;
        } else if (greeting && tokens.size() <= 5) {
            act = Act.SALUDO;
        } else if (preference && firstPerson) {
            act = Act.PREFERENCIA;
        } else if (!feeling.isEmpty() && firstPerson) {
            act = Act.SENTIMIENTO;
        } else if (plan && !imperative) {
            act = Act.PLAN;
        } else if (imperative) {
            act = Act.ORDEN;
        } else if (negation && verb != null && !action.isBlank() && tokens.size() <= 6) {
            // "no cortes el arbol" is a negative order: acknowledged, not executed.
            act = Act.ORDEN;
        } else if (question) {
            act = Act.PREGUNTA_A_COVE;
        } else if (unclear(tokens, verb, items, places, mobs, question, greeting, phrase != null)) {
            // Nothing recognised: say so instead of nodding along.
            act = Act.DUDOSA;
        } else if (pending != null) {
            if (tokens.stream().anyMatch(YES_WORDS::contains) || yesPhrase) {
                act = Act.RESPUESTA_SI;
                answers = true;
            } else if (unsure || normalized.contains("no se") || normalized.contains("ni idea")
                    || normalized.contains("no tengo idea")) {
                // "no se" is not a no: it answers without giving data.
                act = Act.RESPUESTA_DATO;
                answers = true;
            } else if (negation) {
                act = Act.RESPUESTA_NO;
                answers = true;
            } else if (!direction.isEmpty() || number != null || !places.isEmpty()
                    || !items.isEmpty() || !mobs.isEmpty()) {
                act = Act.RESPUESTA_DATO;
                answers = true;
            } else if (tokens.size() <= 6) {
                // A short statement right after a question is a vague answer;
                // a long unrelated sentence is not.
                act = Act.RESPUESTA_DATO;
                answers = true;
            } else {
                act = Act.CHARLA;
            }
        } else {
            act = Act.CHARLA;
        }

        return new Reading(act, action, hint, List.copyOf(items), List.copyOf(places),
                List.copyOf(mobs), direction, number, feeling, negation, addressed,
                incongruent, answers, verb == null ? "" : verb.canonical(), text);
    }

    private static Reading empty(String text) {
        return new Reading(Act.AMBIGUA, "", "", List.of(), List.of(), List.of(), "", null, "",
                false, false, false, false, "", text);
    }

    /**
     * True only for a real command: an imperative inflection (voseo or
     * nosotros), a bare verb message, or an infinitive introduced by
     * "a"/"que"/"de". A plain infinitive inside a statement is not an order.
     */
    private static boolean looksImperative(List<String> tokens, String infinitive) {
        if (tokens.isEmpty()) {
            return false;
        }
        String imperative = Spanish.conjugate(infinitive, Spanish.Tense.IMPERATIVO,
                Spanish.Person.TU);
        String nosotros = Spanish.conjugate(infinitive, Spanish.Tense.IMPERATIVO,
                Spanish.Person.NOSOTROS);
        if (containsForm(tokens, imperative) || containsForm(tokens, nosotros)) {
            return true;
        }
        if (tokens.size() == 1) {
            return true;
        }
        for (int i = 1; i < tokens.size(); i++) {
            if (!stripSuffix(tokens.get(i)).equals(infinitive)) {
                continue;
            }
            String previous = tokens.get(i - 1);
            if (previous.equals("a") || previous.equals("que") || previous.equals("de")
                    || previous.equals("para")) {
                return true;
            }
        }
        return false;
    }

    private static boolean unclear(List<String> tokens, Lexicon.Entry verb, Set<String> items,
                                   Set<String> places, Set<String> mobs, boolean question,
                                   boolean greeting, boolean phrase) {
        if (verb != null || question || greeting || phrase || !items.isEmpty()
                || !places.isEmpty() || !mobs.isEmpty()) {
            return false;
        }
        int unknown = 0;
        for (String token : tokens) {
            if (token.length() >= 4 && !Lexicon.shared().known(token)
                    && !Set.of("para", "pero", "este", "esto", "esa", "ese", "porque",
                    "cuando", "donde", "todo", "muy", "mas", "menos", "algo", "nada",
                    "cosa", "ahora", "despues", "entonces").contains(token)) {
                unknown++;
            }
        }
        return unknown >= 2;
    }

    private static boolean containsForm(List<String> tokens, String form) {
        for (String token : tokens) {
            if (stripSuffix(token).equals(form)) {
                return true;
            }
        }
        return false;
    }

    private static String stripSuffix(String token) {
        for (String suffix : List.of("me", "te", "se", "lo", "la", "le", "nos")) {
            if (token.length() > suffix.length() + 2 && token.endsWith(suffix)) {
                return token.substring(0, token.length() - suffix.length());
            }
        }
        return token;
    }

    private static boolean capitalizedUnknown(String raw, List<String> knownTokens) {
        String[] rawTokens = raw.split("\\s+");
        for (int i = 1; i < rawTokens.length; i++) {
            String clean = rawTokens[i].replaceAll("[^\\p{L}]", "");
            if (clean.length() < 3 || !Character.isUpperCase(clean.charAt(0))) {
                continue;
            }
            if (!knownTokens.contains(Spanish.normalize(clean))) {
                return true;
            }
        }
        return false;
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
