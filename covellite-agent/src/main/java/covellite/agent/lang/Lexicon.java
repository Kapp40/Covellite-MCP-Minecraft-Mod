package covellite.agent.lang;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Cove's knowledge base: what words mean and which mod action a verb maps to.
 *
 * <p>The data lives in {@code /cove/lexicon.txt} as plain text, so it can be
 * reviewed, translated or extended without touching Java. Loading is lazy and
 * happens once; the whole thing is a few kilobytes of RAM.</p>
 */
public final class Lexicon {

    public enum Category {
        ITEM, PLACE, MOB, ADJ, DIR, VERB, GREET, STATE, WORD, FEELING, ABSURD
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
    private final Set<String> unknownProperNouns = new LinkedHashSet<>();

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

    private static Lexicon load() {
        Lexicon lexicon = new Lexicon();
        InputStream stream = Lexicon.class.getResourceAsStream("/cove/lexicon.txt");
        if (stream == null) {
            System.err.println("[cove] lexicon resource missing");
            return lexicon;
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
                    System.err.println("[cove] lexicon line " + lineNumber + " ignored");
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
                lexicon.index(Spanish.normalize(canonical), entry);
                for (String synonym : synonyms) {
                    lexicon.index(Spanish.normalize(synonym), entry);
                }
            }
        } catch (IOException e) {
            System.err.println("[cove] cannot read lexicon: " + e.getMessage());
        }
        return lexicon;
    }

    private void index(String key, Entry entry) {
        if (!key.isEmpty() && !this.byWord.containsKey(key)) {
            this.byWord.put(key, entry);
        }
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
        // Verb inflections (corta, cortando, corto) resolve to the infinitive.
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
        return null;
    }

    /**
     * Finds the verb a token actually inflects, including voseo imperatives
     * ("anda", "mina", "segui") and reflexive suffixes ("seguime").
     * Checked before nouns so "mina" is a verb when the sentence is an order.
     */
    public Entry verbIn(List<String> tokens) {
        for (Entry entry : all(Category.VERB)) {
            String infinitive = entry.canonical();
            String imperative = Spanish.conjugate(infinitive, Spanish.Tense.IMPERATIVO,
                    Spanish.Person.TU);
            String nosotros = Spanish.conjugate(infinitive, Spanish.Tense.IMPERATIVO,
                    Spanish.Person.NOSOTROS);
            String tu = Spanish.conjugate(infinitive, Spanish.Tense.PRESENTE, Spanish.Person.TU);
            String el = Spanish.conjugate(infinitive, Spanish.Tense.PRESENTE, Spanish.Person.EL);
            String gerund = Spanish.conjugate(infinitive, Spanish.Tense.GERUNDIO,
                    Spanish.Person.EL);
            for (String token : tokens) {
                String bare = stripReflexive(token);
                if (bare.equals(imperative) || bare.equals(nosotros) || bare.equals(infinitive)
                        || bare.equals(gerund) || bare.equals(tu) || bare.equals(el)) {
                    return entry;
                }
            }
        }
        return null;
    }

    private static String stripReflexive(String token) {
        for (String suffix : List.of("me", "te", "se", "lo", "la", "le", "nos")) {
            if (token.length() > suffix.length() + 2 && token.endsWith(suffix)) {
                return token.substring(0, token.length() - suffix.length());
            }
        }
        return token;
    }

    public Entry verb(String infinitiveOrInflection) {
        Entry entry = lookup(infinitiveOrInflection);
        return entry != null && entry.verb() ? entry : null;
    }

    public boolean known(String token) {
        return lookup(token) != null;
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

    public int size() {
        return this.byWord.size();
    }

    /** Words that look like a proper noun Cove has never heard of. */
    public void noteUnknown(String word) {
        this.unknownProperNouns.add(Spanish.normalize(word));
    }

    public Set<String> unknownWords() {
        return Set.copyOf(this.unknownProperNouns);
    }
}
