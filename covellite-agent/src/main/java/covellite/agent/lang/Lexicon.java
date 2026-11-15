package covellite.agent.lang;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Cove's knowledge base: what words mean, which mod action a verb maps to, and
 * which casual phrases carry a tone.
 *
 * <p>Data lives in plain text so it can be reviewed, extended and even trained
 * without touching Java:</p>
 *
 * <ul>
 *   <li>{@code /cove/lexicon.txt} — the curated base shipped in the jar.</li>
 *   <li>{@code /cove/lexicon-extra.txt} — optional additions packaged with it.</li>
 *   <li>{@code <gamedir>/covemcp/lexicon-extra.txt} — the file a player can edit
 *       at runtime; the mod points to it through the
 *       {@code covemcp.lexiconExtra} system property. Same format.</li>
 * </ul>
 *
 * <p>Every verb is indexed in all its tenses and in the conjugations of its
 * synonym infinitives, plus multiword phrases, so casual speech resolves without
 * a language model. Loading happens once and costs a few kilobytes.</p>
 */
public final class Lexicon {

    public enum Category {
        ITEM, PLACE, MOB, ADJ, DIR, VERB, GREET, STATE, WORD, FEELING, ABSURD, PHRASE, TUNNEL
    }

    /** One word with its synonyms and, for verbs, the action it refers to. */
    public record Entry(String canonical, Category category, List<String> synonyms,
                        String action, String hint) {

        public boolean verb() {
            return this.category == Category.VERB;
        }
    }

    private static volatile Lexicon shared;

    private final Map<String, Entry> byWord = new LinkedHashMap<>();
    private final Map<String, Entry> verbForms = new LinkedHashMap<>();
    private final Map<String, Entry> phrases = new LinkedHashMap<>();
    private final Set<String> imperativeForms = new LinkedHashSet<>();
    /** Words that ask for a ramp/tunnel instead of a surface search. */
    private final Set<String> tunnelWords = new LinkedHashSet<>();
    /** Recent typo corrections, so a miss does not rescan the whole lexicon. */
    private final Map<String, Entry> typoHits = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> typoMisses = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private List<Map.Entry<String, Entry>> phrasesByLength = List.of();

    private Lexicon() {
    }

    public static Lexicon shared() {
        Lexicon current = shared;
        if (current == null) {
            synchronized (Lexicon.class) {
                current = shared;
                if (current == null) {
                    current = load();
                    shared = current;
                }
            }
        }
        return current;
    }

    /** Forgets the cached lexicon so an edited extra file can be picked up. */
    public static void reload() {
        synchronized (Lexicon.class) {
            shared = null;
        }
    }

    private static Lexicon load() {
        Lexicon lexicon = new Lexicon();
        lexicon.loadStream(Lexicon.class.getResourceAsStream("/cove/lexicon.txt"));
        // Generated from Minecraft's own language files: every item, block, mob,
        // biome and effect in Spanish with its English name as an alias.
        lexicon.loadStream(Lexicon.class.getResourceAsStream("/cove/minecraft-terms.txt"));
        lexicon.loadStream(Lexicon.class.getResourceAsStream("/cove/lexicon-extra.txt"));
        String extraPath = System.getProperty("covemcp.lexiconExtra");
        if (extraPath != null && !extraPath.isBlank()) {
            Path path = Path.of(extraPath);
            if (Files.isReadable(path)) {
                try (InputStream stream = Files.newInputStream(path)) {
                    lexicon.loadStream(stream);
                } catch (IOException e) {
                    System.err.println("[cove] no se pudo leer " + path + ": " + e.getMessage());
                }
            }
        }
        // Everything Cove learned while playing is written here by Learning, in
        // the same format, so it is simply more base knowledge on the next load.
        String learnedPath = System.getProperty("covemcp.lexiconLearned");
        if (learnedPath != null && !learnedPath.isBlank()) {
            Path path = Path.of(learnedPath);
            if (Files.isReadable(path)) {
                try (InputStream stream = Files.newInputStream(path)) {
                    lexicon.loadStream(stream);
                } catch (IOException e) {
                    System.err.println("[cove] no se pudo leer " + path + ": " + e.getMessage());
                }
            }
        }
        lexicon.phrasesByLength = new ArrayList<>(lexicon.phrases.entrySet());
        lexicon.phrasesByLength.sort(Comparator.comparingInt(
                (Map.Entry<String, Entry> entry) -> entry.getKey().length()).reversed());
        return lexicon;
    }

    private void loadStream(InputStream stream) {
        if (stream == null) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split("\\|", -1);
                if (parts.length < 3) {
                    System.err.println("[cove] linea de lexico ignorada: " + lineNumber);
                    continue;
                }
                Category category;
                try {
                    category = Category.valueOf(parts[0].trim().toUpperCase(Locale.ROOT));
                } catch (RuntimeException e) {
                    continue;
                }
                String canonical = parts[1].trim();
                if (canonical.isEmpty()) {
                    continue;
                }
                List<String> synonyms = new ArrayList<>();
                for (String synonym : parts[2].split(",")) {
                    String clean = synonym.trim();
                    if (!clean.isEmpty()) {
                        synonyms.add(clean);
                    }
                }
                String action = parts.length > 3 ? parts[3].trim() : "";
                String hint = parts.length > 4 ? parts[4].trim() : "";
                Entry entry = new Entry(canonical, category, List.copyOf(synonyms), action, hint);
                if (category == Category.TUNNEL) {
                    this.tunnelWords.add(Spanish.normalize(canonical));
                    for (String synonym : synonyms) {
                        this.tunnelWords.add(Spanish.normalize(synonym));
                    }
                    continue;
                }
                if (category == Category.PHRASE) {
                    this.phrases.putIfAbsent(Spanish.normalize(canonical), entry);
                    for (String synonym : synonyms) {
                        this.phrases.putIfAbsent(Spanish.normalize(synonym), entry);
                    }
                    continue;
                }
                index(Spanish.normalize(canonical), entry);
                for (String synonym : synonyms) {
                    index(Spanish.normalize(synonym), entry);
                }
                if (category == Category.VERB) {
                    indexVerbForms(entry);
                }
            }
        } catch (IOException e) {
            System.err.println("[cove] no se pudo leer el lexico: " + e.getMessage());
        }
    }

    private void index(String key, Entry entry) {
        if (!key.isEmpty() && !this.byWord.containsKey(key)) {
            this.byWord.put(key, entry);
        }
    }

    /**
     * Indexes every conjugated form of a verb, including the infinitives hidden
     * in its synonyms: "talar" is a synonym of "cortar", so "tala", "talás" and
     * "talaríamos" must resolve to the same action. Casual speech also needs the
     * tenses, not only the present.
     */
    private void indexVerbForms(Entry entry) {
        List<String> infinitives = new ArrayList<>();
        infinitives.add(Spanish.normalize(entry.canonical()));
        for (String synonym : entry.synonyms()) {
            String clean = Spanish.normalize(synonym);
            if (clean.matches("[a-z]{2,}(ar|er|ir)")) {
                infinitives.add(clean);
            }
        }
        for (String infinitive : infinitives) {
            putForm(infinitive, entry);
            for (Spanish.Person person : Spanish.Person.values()) {
                for (Spanish.Tense tense : List.of(Spanish.Tense.PRESENTE,
                        Spanish.Tense.PRETERITO, Spanish.Tense.IMPERFECTO,
                        Spanish.Tense.FUTURO, Spanish.Tense.CONDICIONAL,
                        Spanish.Tense.SUBJUNTIVO)) {
                    putForm(Spanish.conjugate(infinitive, tense, person), entry);
                }
            }
            String tu = Spanish.conjugate(infinitive, Spanish.Tense.IMPERATIVO,
                    Spanish.Person.TU);
            String nosotros = Spanish.conjugate(infinitive, Spanish.Tense.IMPERATIVO,
                    Spanish.Person.NOSOTROS);
            putForm(tu, entry);
            putForm(nosotros, entry);
            this.imperativeForms.add(Spanish.normalize(tu));
            this.imperativeForms.add(Spanish.normalize(nosotros));
            putForm(Spanish.conjugate(infinitive, Spanish.Tense.GERUNDIO, Spanish.Person.EL), entry);
            putForm(Spanish.conjugate(infinitive, Spanish.Tense.PARTICIPIO, Spanish.Person.EL), entry);
        }
    }

    private void putForm(String form, Entry entry) {
        String clean = Spanish.normalize(form);
        if (clean.isBlank() || clean.length() < 3) {
            return;
        }
        this.verbForms.putIfAbsent(clean, entry);
    }

    /** Exact or singular/plural tolerant lookup. */
    public Entry lookup(String rawToken) {
        String key = Spanish.normalize(rawToken);
        if (key.isEmpty()) {
            return null;
        }
        Entry direct = this.byWord.get(key);
        if (direct != null) {
            return direct;
        }
        String singular = Spanish.singular(key);
        Entry entry = this.byWord.get(singular);
        if (entry != null) {
            return entry;
        }
        for (Map.Entry<String, Entry> candidate : this.byWord.entrySet()) {
            if (!candidate.getValue().verb()) {
                continue;
            }
            int cut = Math.min(candidate.getKey().length(),
                    Math.max(2, candidate.getKey().length() - 2));
            String stem = candidate.getKey().substring(0, cut);
            if (stem.length() >= 3 && key.startsWith(stem)
                    && key.length() <= candidate.getKey().length() + 3) {
                return candidate.getValue();
            }
        }
        // Typed in a hurry: "diamnte", "cortr el arbol". One edit away is a
        // typo, not a new word; two only for long words.
        Entry typo = fuzzy(key);
        if (typo != null) {
            return typo;
        }
        return null;
    }

    /**
     * Nearest known word within one edit (two for long words), cached. Words under
     * five letters are skipped on purpose: correcting "cazo" into "capo" invents a
     * compliment and is worse than admitting the word is unknown.
     */
    public Entry fuzzy(String key) {
        if (key == null || key.length() < 5) {
            return null;
        }
        Entry cached = this.typoHits.get(key);
        if (cached != null) {
            return cached;
        }
        if (this.typoMisses.contains(key)) {
            return null;
        }
        int limit = key.length() >= 8 ? 2 : 1;
        Entry best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Map.Entry<String, Entry> candidate : this.byWord.entrySet()) {
            String word = candidate.getKey();
            if (Math.abs(word.length() - key.length()) > limit) {
                continue;
            }
            int distance = distance(key, word, limit);
            if (distance < bestDistance && distance <= limit) {
                bestDistance = distance;
                best = candidate.getValue();
                if (distance == 1) {
                    break;
                }
            }
        }
        if (best == null) {
            for (Map.Entry<String, Entry> candidate : this.verbForms.entrySet()) {
                String word = candidate.getKey();
                if (Math.abs(word.length() - key.length()) > limit) {
                    continue;
                }
                int distance = distance(key, word, limit);
                if (distance < bestDistance && distance <= limit) {
                    bestDistance = distance;
                    best = candidate.getValue();
                }
            }
        }
        if (best == null) {
            this.typoMisses.add(key);
        } else {
            this.typoHits.put(key, best);
        }
        return best;
    }

    /** Optimal string alignment distance, bounded by {@code limit}. */
    private static int distance(String a, String b, int limit) {
        if (a.equals(b)) {
            return 0;
        }
        if (Math.abs(a.length() - b.length()) > limit) {
            return limit + 1;
        }
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        int[] beforePrevious = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2)
                        && a.charAt(i - 2) == b.charAt(j - 1)) {
                    current[j] = Math.min(current[j], beforePrevious[j - 2] + 1);
                }
            }
            int[] swap = beforePrevious;
            beforePrevious = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    /** True when the message asks to dig down ("cavá un túnel", "bajá buscando hierro"). */
    public boolean tunnelIntent(String normalizedText) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return false;
        }
        String padded = " " + normalizedText + " ";
        for (String word : this.tunnelWords) {
            if (!word.isBlank() && padded.contains(" " + word + " ")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds a word learned mid-game. Same indices as a file entry, so the next
     * message already understands it; the file keeps it for the next session.
     */
    public synchronized void addLearned(Entry entry) {
        if (entry == null || entry.canonical() == null || entry.canonical().isBlank()) {
            return;
        }
        index(Spanish.normalize(entry.canonical()), entry);
        for (String synonym : entry.synonyms()) {
            index(Spanish.normalize(synonym), entry);
        }
        if (entry.category() == Category.VERB) {
            indexVerbForms(entry);
        }
        this.typoHits.clear();
        this.typoMisses.clear();
    }

    /** True when the token is an imperative form of any known verb. */
    public boolean isImperative(String token) {
        return this.imperativeForms.contains(stripReflexive(Spanish.normalize(token)));
    }

    /**
     * Finds the verb a token actually inflects, including voseo imperatives
     * ("andá", "miná", "seguí"), reflexive suffixes ("seguime") and any tense.
     */
    public Entry verbIn(List<String> tokens) {
        for (String token : tokens) {
            Entry entry = this.verbForms.get(stripReflexive(token));
            if (entry != null) {
                return entry;
            }
        }
        for (String token : tokens) {
            String bare = stripReflexive(token);
            if (bare.length() < 4) {
                continue;
            }
            for (Map.Entry<String, Entry> form : this.verbForms.entrySet()) {
                if (bare.startsWith(form.getKey())
                        && bare.length() - form.getKey().length() <= 3) {
                    return form.getValue();
                }
            }
        }
        return null;
    }

    /** Longest multiword expression contained in the normalized sentence. */
    public Entry phraseIn(String normalizedText) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return null;
        }
        String padded = " " + normalizedText + " ";
        for (Map.Entry<String, Entry> phrase : this.phrasesByLength) {
            if (padded.contains(" " + phrase.getKey() + " ")) {
                return phrase.getValue();
            }
        }
        return null;
    }

    public boolean known(String token) {
        return lookup(token) != null || verbIn(List.of(Spanish.normalize(token))) != null;
    }

    public List<Entry> all(Category category) {
        List<Entry> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Entry entry : this.byWord.values()) {
            if (entry.category() == category && seen.add(entry.canonical())) {
                result.add(entry);
            }
        }
        return result;
    }

    /** Indexed words, verb forms and phrases. Useful as a sanity number. */
    public int size() {
        return this.byWord.size() + this.verbForms.size() + this.phrases.size();
    }

    public int verbFormCount() {
        return this.verbForms.size();
    }

    public int phraseCount() {
        return this.phrases.size();
    }

    public int tunnelWordCount() {
        return this.tunnelWords.size();
    }

    private static String stripReflexive(String token) {
        for (String suffix : List.of("me", "te", "se", "lo", "la", "le", "nos")) {
            if (token.length() > suffix.length() + 2 && token.endsWith(suffix)) {
                return token.substring(0, token.length() - suffix.length());
            }
        }
        return token;
    }
}
