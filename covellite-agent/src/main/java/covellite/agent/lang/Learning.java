package covellite.agent.lang;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Learns vocabulary while playing.
 *
 * <p>A closed lexicon is the weakest part of an offline brain: players type
 * words Cove does not have. Instead of asking them to edit a file and restart,
 * this class takes definitions from the conversation itself:</p>
 *
 * <pre>
 *   "el cuarzo rosa es un bloque"      -> ITEM   cuarzo rosa
 *   "el warden es un bicho"            -> MOB    warden
 *   "una granja es un lugar"           -> PLACE  granja
 *   "wapiti significa ciervo"          -> synonym of an existing word
 *   "¿qué es un sniffer?"              -> asks back, then learns the answer
 * </pre>
 *
 * <p>What it learns is appended to a plain text file in the game directory
 * ({@code covemcp/lexicon-learned.txt}) with the same format as the base
 * lexicon, so it survives a restart and can be reviewed or edited by hand. The
 * live lexicon is updated immediately, no reload needed.</p>
 */
public final class Learning {

    /** Result of reading one message: nothing, a learned word, or a question back. */
    public record Result(Kind kind, String word, String category) {

        public static Result none() {
            return new Result(Kind.NONE, "", "");
        }

        public static Result learned(String word) {
            return new Result(Kind.LEARNED, word, "");
        }

        public static Result asked(String word) {
            return new Result(Kind.ASKED, word, "");
        }
    }

    public enum Kind { NONE, LEARNED, ASKED }

    /** Word -> what it is. Used by the test and by the prompt builder. */
    public record Learned(String word, Lexicon.Category category, String detail) {
    }

    private static final String[] CATEGORY_WORDS = {
            "item", "bloque", "material", "mineral", "comida", "animal", "bicho", "monstruo",
            "mob", "lugar", "zona", "bioma", "palabra", "sentimiento", "verbo", "herramienta",
            "arma", "color", "cosa"
    };

    private static final String CATEGORY_PATTERN = String.join("|", CATEGORY_WORDS);

    /** "el warden es un bicho", "cuarzo rosa es un bloque", "una granja es un lugar". */
    private static final Pattern IS_A = Pattern.compile(
            "^(?:che |cove |ojo |dale |mira |bueno )*"
                    + "(?:el|la|los|las|un|una|unos|unas)?\\s*"
                    + "([\\p{L}][\\p{L}\\s]{0,24}?)\\s+"
                    + "(?:es|son|era|seria|es como)\\s+"
                    + "(?:un|una|el|la|los|las)?\\s*"
                    + "(" + CATEGORY_PATTERN + ")s?\\b"
                    + "(?:\\s+(?:que\\s+|de\\s+|para\\s+)?([\\p{L}\\s]{1,24}))?\\s*$");

    /** "wapiti significa ciervo", "a la papa le decimos patata", "sniffer se dice sniffle". */
    private static final Pattern MEANS = Pattern.compile(
            "^(?:che |cove )*(?:a\\s+)?(?:el|la|los|las)?\\s*([\\p{L}][\\p{L}\\s]{0,24}?)\\s+"
                    + "(?:significa|quiere decir|se dice|le decimos|le dicen|=)\\s+"
                    + "(?:es\\s+)?([\\p{L}][\\p{L}\\s]{0,24})\\s*$");

    /** "¿qué es un sniffer?", "qué significa wapiti", "che, que es eso". */
    private static final Pattern ASKS = Pattern.compile(
            "^(?:che |cove |decime |deci |posta )*"
                    + "(?:que|qué)\\s+(?:es|son|significa|quiere decir)\\s+"
                    + "(?:un|una|el|la|los|las)?\\s*([\\p{L}][\\p{L}\\s]{0,24})\\s*$");

    private static final List<Learned> SESSION = new ArrayList<>();

    private String pendingWord = "";

    public static List<Learned> session() {
        synchronized (SESSION) {
            return List.copyOf(SESSION);
        }
    }

    public boolean hasPending() {
        return !this.pendingWord.isEmpty();
    }

    public String pendingWord() {
        return this.pendingWord;
    }

    public void clearPending() {
        this.pendingWord = "";
    }

    /**
     * Reads one player message and, when it teaches or asks for a definition,
     * does the work. Never throws: a malformed definition is ignored.
     */
    public Result consume(String raw) {
        String text = Spanish.normalize(raw);
        if (text.isBlank()) {
            return Result.none();
        }
        // An answer to "¿qué es X?" is a definition: "es un bloque", "es una
        // fruta dulce", "significa ciervo". The subject is the pending word.
        if (!this.pendingWord.isEmpty()) {
            Result answered = answerPending(text, raw);
            if (answered.kind() != Kind.NONE) {
                return answered;
            }
        }

        Matcher asks = ASKS.matcher(text);
        if (asks.matches()) {
            String word = clean(asks.group(1));
            if (!word.isBlank() && !Lexicon.shared().known(word)
                    && Lexicon.shared().lookup(word) == null) {
                this.pendingWord = word;
                return Result.asked(word);
            }
            return Result.none();
        }

        Matcher means = MEANS.matcher(text);
        if (means.matches()) {
            String word = clean(means.group(1));
            String meaning = clean(means.group(2));
            if (!word.isBlank() && !meaning.isBlank() && !word.equals(meaning)) {
                return learnSynonym(word, meaning);
            }
        }

        Matcher isA = IS_A.matcher(text);
        if (isA.matches()) {
            String word = clean(isA.group(1));
            String categoryWord = clean(isA.group(2));
            String detail = isA.group(3) == null ? "" : clean(isA.group(3));
            if (!word.isBlank()) {
                return learnCategory(word, categoryWord, detail);
            }
        }
        return Result.none();
    }

    private Result answerPending(String text, String raw) {
        String word = this.pendingWord;
        String payload = raw == null ? "" : raw.trim();
        // "es un bloque", "es una fruta", "significa ciervo", "es como un
        // zombie pero azul": all carry the definition after the verb.
        Matcher isA = Pattern.compile(
                "^(?:es|son|seria|significa|quiere decir|y |bueno |no,? )*\\s*"
                        + "(?:un|una|el|la|los|las)?\\s*(" + CATEGORY_PATTERN + ")s?\\b(.*)$")
                .matcher(Spanish.normalize(payload));
        if (isA.find()) {
            String categoryWord = isA.group(1);
            String detail = isA.group(2) == null ? "" : clean(isA.group(2));
            Result result = learnCategory(word, categoryWord, detail);
            if (result.kind() == Kind.LEARNED) {
                this.pendingWord = "";
            }
            return result;
        }
        Matcher means = Pattern.compile(
                "^(?:es|son|significa|quiere decir|o sea|como)\\s+(?:un|una|el|la)?\\s*([\\p{L}][\\p{L}\\s]{0,24})$")
                .matcher(Spanish.normalize(payload));
        if (means.matches()) {
            String meaning = clean(means.group(1));
            Result result = learnSynonym(word, meaning);
            if (result.kind() == Kind.LEARNED) {
                this.pendingWord = "";
            }
            return result;
        }
        // The player changed the subject: stop waiting for the definition.
        if (Spanish.tokens(text).size() > 6) {
            this.pendingWord = "";
        }
        return Result.none();
    }

    private Result learnCategory(String word, String categoryWord, String detail) {
        Lexicon.Category category = switch (categoryWord) {
            case "lugar", "zona", "bioma" -> Lexicon.Category.PLACE;
            case "animal", "bicho", "monstruo", "mob" -> Lexicon.Category.MOB;
            case "sentimiento" -> Lexicon.Category.FEELING;
            case "palabra" -> Lexicon.Category.WORD;
            case "verbo" -> Lexicon.Category.WORD;
            default -> Lexicon.Category.ITEM;
        };
        Lexicon.Entry entry = new Lexicon.Entry(word, category, List.of(), "",
                detail.isBlank() ? "" : detail);
        persist(entry);
        Lexicon.shared().addLearned(entry);
        record(word, category, detail);
        return Result.learned(word);
    }

    private Result learnSynonym(String word, String meaning) {
        Lexicon.Entry known = Lexicon.shared().lookup(meaning);
        if (known != null && !known.canonical().equalsIgnoreCase(word)) {
            Lexicon.Entry entry = new Lexicon.Entry(known.canonical(), known.category(),
                    List.of(word), known.action(), known.hint());
            persist(entry);
            Lexicon.shared().addLearned(entry);
            record(word, known.category(), known.canonical());
            return Result.learned(word);
        }
        Lexicon.Entry entry = new Lexicon.Entry(word, Lexicon.Category.WORD, List.of(meaning), "", "");
        persist(entry);
        Lexicon.shared().addLearned(entry);
        record(word, Lexicon.Category.WORD, meaning);
        return Result.learned(word);
    }

    private static void record(String word, Lexicon.Category category, String detail) {
        synchronized (SESSION) {
            SESSION.add(new Learned(word, category, detail));
            while (SESSION.size() > 400) {
                SESSION.remove(0);
            }
        }
    }

    /** Appends the entry to the learned file, one line per synonym set. */
    private static void persist(Lexicon.Entry entry) {
        Path file = file();
        if (file == null) {
            return;
        }
        String line = entry.category().name() + "|" + entry.canonical() + "|"
                + String.join(",", entry.synonyms()) + "|" + nullToEmpty(entry.action()) + "|"
                + nullToEmpty(entry.hint());
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[cove] no se pudo guardar lo aprendido: " + e.getMessage());
        }
    }

    /** The learned file for this world, or null when the mod did not point one. */
    public static Path file() {
        String path = System.getProperty("covemcp.lexiconLearned");
        return path == null || path.isBlank() ? null : Path.of(path);
    }

    /** Everything learned so far in this session, for the panel and the docs. */
    public static Map<String, String> summary() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Learned learned : session()) {
            out.put(learned.word(), learned.category().name().toLowerCase(Locale.ROOT)
                    + (learned.detail().isBlank() ? "" : ": " + learned.detail()));
        }
        return out;
    }

    private static String clean(String text) {
        if (text == null) {
            return "";
        }
        return Spanish.normalize(text).replaceAll("^(un|una|el|la|los|las) ", "").trim();
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    /** Unused in production; kept for tests that want a deterministic reset. */
    static void resetSession() {
        synchronized (SESSION) {
            SESSION.clear();
        }
    }

    /** Never learned anything for this word yet. */
    public static Optional<Learned> find(String word) {
        for (Learned learned : session()) {
            if (learned.word().equalsIgnoreCase(word)) {
                return Optional.of(learned);
            }
        }
        return Optional.empty();
    }
}
