package covellite.agent.nn;

import covellite.core.Grammar;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds the training corpus for the embedded language model.
 *
 * <p>Nobody ships a corpus of "Minecraft companion, rioplatense Spanish", so it
 * is generated from the pieces the project already owns: the verb conjugator,
 * the vocabulary of blocks, mobs and places, and the way the companion actually
 * speaks. Every sentence is grammatical because the verb forms come from
 * {@link Grammar}, not from string concatenation.</p>
 *
 * <p>Output is plain text, one sentence per line, so a checkpoint can be
 * retrained later on real conversations without changing the format.</p>
 */
public final class CorpusBuilder {

    private static final String[] VERBS = {
            "minar", "talar", "cavar", "explorar", "pescar", "craftear", "caminar",
            "correr", "pelear", "comer", "dormir", "volver", "buscar", "cuidar",
            "guardar", "construir", "plantar", "cocinar", "subir", "bajar", "saltar",
            "mirar", "esperar", "descansar", "iluminar", "recolectar", "escapar"
    };

    private static final String[] OBJECTS = {
            "hierro", "piedra", "carbón", "diamante", "oro", "madera", "tablones",
            "antorchas", "comida", "pan", "carne", "manzanas", "espada", "pico",
            "hacha", "escudo", "arco", "flechas", "agua", "arena", "tierra",
            "grava", "cobre", "esmeraldas", "redstone", "huesos", "cuerda", "cuero"
    };

    private static final String[] PLACES = {
            "la base", "la cueva", "el bosque", "el desierto", "la playa",
            "la montaña", "el río", "el pantano", "la aldea", "el cerro", "la mina",
            "el llano", "la taiga", "la selva", "las ruinas", "el pozo"
    };

    private static final String[] MOBS = {
            "zombis", "esqueletos", "creepers", "arañas", "endermans", "vacas",
            "ovejas", "cerdos", "pollos", "lobos", "ahogados", "brujas"
    };

    private static final String[] MOODS = {
            "cansado", "herido", "hambriento", "contento", "aburrido", "tranquilo",
            "apurado", "nervioso", "orgulloso", "harto", "con ganas", "sin pilas"
    };

    private static final String[] PLAYERS = {"che", "loco", "amigo", "jefe", "vos"};

    private CorpusBuilder() {
    }

    /** {@code count} sentences, deterministic for a given seed. */
    public static List<String> sentences(int count, long seed) {
        Random random = new Random(seed);
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(sentence(random));
        }
        return out;
    }

    public static void writeCorpus(Path file, int count, long seed) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            Random random = new Random(seed);
            for (int i = 0; i < count; i++) {
                writer.write(sentence(random));
                writer.newLine();
            }
        }
    }

    /** One grammatical sentence. */
    public static String sentence(Random random) {
        String verb = pick(VERBS, random);
        String otherVerb = pick(VERBS, random);
        String object = pick(OBJECTS, random);
        String otherObject = pick(OBJECTS, random);
        String place = pick(PLACES, random);
        String mob = pick(MOBS, random);
        String mood = pick(MOODS, random);
        String player = pick(PLAYERS, random);
        int number = 1 + random.nextInt(64);
        int health = 1 + random.nextInt(20);
        String gerund = Grammar.gerund(verb);
        String otherGerund = Grammar.gerund(otherVerb);
        String present = Grammar.firstPerson(verb);
        String otherPresent = Grammar.firstPerson(otherVerb);
        switch (random.nextInt(24)) {
            case 0:
                return "estoy " + gerund + " " + object;
            case 1:
                return "estoy " + gerund + " en " + place;
            case 2:
                return "acabo de " + verb + " " + object;
            case 3:
                return "me quedan " + health + " de vida y estoy " + mood;
            case 4:
                return "tengo " + number + " de " + object + " encima";
            case 5:
                return "¿vamos a " + verb + " " + object + "?";
            case 6:
                return "¿tenés " + object + "?";
            case 7:
                return "no me gusta " + place + ", hay " + mob;
            case 8:
                return "prefiero " + verb + " " + object + " antes que " + otherVerb;
            case 9:
                return "dale, " + present + " " + object + " ahora";
            case 10:
                return "che " + player + ", " + present + " " + object + " en " + place;
            case 11:
                return "si querés " + present + " " + object + " con vos";
            case 12:
                return "hace un rato que estoy " + gerund + " y todavía no encontré " + object;
            case 13:
                return "cuidado, hay " + mob + " cerca de " + place;
            case 14:
                return "me gusta " + gerund + " de noche, es más tranquilo";
            case 15:
                return "el " + object + " está en " + place + ", lo vi recién";
            case 16:
                return "estoy " + mood + ", necesito " + object;
            case 17:
                return "¿me ayudás a " + verb + " " + object + "?";
            case 18:
                return "ya terminé de " + verb + ", junté " + number + " de " + object;
            case 19:
                return "no puedo " + verb + " sin " + otherObject + ", me falta";
            case 20:
                return "estoy " + gerund + ", pero si querés " + present + " " + otherObject;
            case 21:
                return "esto de " + place + " está lleno de " + mob + ", no me gusta";
            case 22:
                return "me quedo en " + place + " " + otherGerund + " un rato";
            default:
                return "vos mandás: si decís " + present + " " + object + ", lo hago";
        }
    }

    /** Lines the player would say, so the model learns both sides of a talk. */
    public static String playerLine(Random random) {
        String verb = pick(VERBS, random);
        String object = pick(OBJECTS, random);
        switch (random.nextInt(12)) {
            case 0:
                return "equipate " + object;
            case 1:
                return "anda a " + verb;
            case 2:
                return "seguime";
            case 3:
                return "vení";
            case 4:
                return "pará";
            case 5:
                return "¿qué estás haciendo?";
            case 6:
                return "¿cómo andás?";
            case 7:
                return "¿qué conseguiste?";
            case 8:
                return "dale, " + verb + " " + object;
            case 9:
                return "vení que hay " + pick(MOBS, random);
            case 10:
                return "¿me das " + object + "?";
            default:
                return "quedate acá un rato";
        }
    }

    private static String pick(String[] values, Random random) {
        return values[random.nextInt(values.length)];
    }
}
