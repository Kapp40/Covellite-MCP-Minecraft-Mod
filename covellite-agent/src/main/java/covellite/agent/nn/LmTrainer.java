package covellite.agent.nn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Trains the embedded language model on this machine.
 *
 * <pre>
 * java -cp covellite-agent.jar covellite.agent.nn.LmTrainer [corpus] [epochs] [out] [context] [dim] [hidden]
 * </pre>
 *
 * <p>Everything happens in Java: no Python, no GPU, no network. The checkpoint it
 * writes is the file that ships inside the mod, so what is trained here is
 * exactly what runs in the game.</p>
 */
public final class LmTrainer {

    private static final int DEFAULT_SENTENCES = 40_000;
    private static final int VOCAB_LIMIT = 8_000;

    private LmTrainer() {
    }

    public static void main(String[] args) throws IOException {
        Path corpus = Path.of(args.length > 0 ? args[0] : "build/lm/corpus.txt");
        int epochs = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        Path out = Path.of(args.length > 2 ? args[2] : "build/lm/cove-lm.bin");
        int context = args.length > 3 ? Integer.parseInt(args[3]) : 4;
        int dim = args.length > 4 ? Integer.parseInt(args[4]) : 32;
        int hidden = args.length > 5 ? Integer.parseInt(args[5]) : 128;
        TinyLm model = train(corpus, epochs, out, context, dim, hidden);
        System.out.println("modelo: " + model.parameterCount() + " parametros, "
                + model.samplesSeen() + " muestras");
        for (int i = 0; i < 8; i++) {
            System.out.println("  > " + model.generate(new int[0], 18, 0.8F, new Random(i)));
        }
    }

    public static TinyLm train(Path corpus, int epochs, Path out, int context, int dim,
                               int hidden) throws IOException {
        if (!Files.exists(corpus) || Files.size(corpus) < 1000L) {
            CorpusBuilder.writeCorpus(corpus, DEFAULT_SENTENCES, 7L);
        }
        List<String> lines = Files.readAllLines(corpus, StandardCharsets.UTF_8);
        Vocab vocab = new Vocab();
        for (String line : lines) {
            for (String token : TinyLm.tokenize(line)) {
                vocab.count(token);
            }
        }
        vocab.freeze(VOCAB_LIMIT);
        TinyLm model = new TinyLm(vocab, context, dim, hidden, new Random(42L));
        List<int[]> encoded = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (!line.isBlank()) {
                encoded.add(model.encode(line));
            }
        }
        float loss = model.trainCorpus(encoded, epochs, 0.08F);
        System.out.printf("epochs=%d frases=%d perdida=%.4f%n", epochs, encoded.size(), loss);
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        model.save(out);
        return model;
    }
}
