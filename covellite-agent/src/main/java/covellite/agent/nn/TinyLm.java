package covellite.agent.nn;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A small word-level language model: embeddings, one hidden layer, softmax.
 *
 * <p>It is trained with online stochastic gradient descent and can be resumed,
 * retrained on real conversations and shipped as a checkpoint. The point is not
 * to compete with a big model: it is to <em>generate</em> language instead of
 * picking a stored line, in a couple of megabytes, on the same JVM as the game.</p>
 */
public final class TinyLm {

    private static final int FORMAT = 1;
    private static final float UNK_PENALTY = 0.5F;

    private final Vocab vocab;
    private final int context;
    private final int dim;
    private final int hidden;

    private final Tensor embedding;
    private final Tensor w1;
    private final float[] b1;
    private final Tensor w2;
    private final float[] b2;

    /** Reused buffers: training and inference must not churn the heap. */
    private final float[] x;
    private final float[] z;
    private final float[] h;
    private final float[] logits;
    private final float[] dLogits;
    private final float[] dHidden;
    private final float[] dInput;

    private long samplesSeen;

    public TinyLm(Vocab vocab, int context, int dim, int hidden, Random random) {
        this.vocab = vocab;
        this.context = context;
        this.dim = dim;
        this.hidden = hidden;
        int vocabulary = Math.max(3, vocab.size());
        this.embedding = Tensor.random(vocabulary, dim, 0.1F, random);
        this.w1 = Tensor.random(context * dim, hidden, (float) (1.0D / Math.sqrt(context * dim)), random);
        this.b1 = new float[hidden];
        this.w2 = Tensor.random(hidden, vocabulary, (float) (1.0D / Math.sqrt(hidden)), random);
        this.b2 = new float[vocabulary];
        this.x = new float[context * dim];
        this.z = new float[hidden];
        this.h = new float[hidden];
        this.logits = new float[vocabulary];
        this.dLogits = new float[vocabulary];
        this.dHidden = new float[hidden];
        this.dInput = new float[context * dim];
    }

    public Vocab vocab() {
        return this.vocab;
    }

    public int context() {
        return this.context;
    }

    public int dim() {
        return this.dim;
    }

    public int hidden() {
        return this.hidden;
    }

    public long samplesSeen() {
        return this.samplesSeen;
    }

    /** Total trainable values, to report the model size. */
    public int parameterCount() {
        return this.embedding.size() + this.w1.size() + this.b1.length
                + this.w2.size() + this.b2.length;
    }

    /** Token at window position {@code t}, padding on the left when short. */
    private int tokenAt(int[] ids, int t) {
        int index = ids.length - this.context + t;
        return index >= 0 && index < ids.length ? ids[index] : Vocab.PAD;
    }

    private void embed(int[] ids) {
        float[] data = this.embedding.data();
        for (int t = 0; t < this.context; t++) {
            int from = tokenAt(ids, t) * this.dim;
            System.arraycopy(data, from, this.x, t * this.dim, this.dim);
        }
    }

    /** Forward pass: the unnormalised score for every word. */
    public float[] logits(int[] ids) {
        embed(ids);
        float[] w1Data = this.w1.data();
        java.util.Arrays.fill(this.z, 0.0F);
        for (int k = 0; k < this.x.length; k++) {
            float value = this.x[k];
            if (value == 0.0F) {
                continue;
            }
            int row = k * this.hidden;
            for (int i = 0; i < this.hidden; i++) {
                this.z[i] += value * w1Data[row + i];
            }
        }
        for (int i = 0; i < this.hidden; i++) {
            this.z[i] += this.b1[i];
            this.h[i] = this.z[i] > 0.0F ? this.z[i] : 0.0F;
        }
        float[] w2Data = this.w2.data();
        int vocabulary = this.b2.length;
        java.util.Arrays.fill(this.logits, 0.0F);
        for (int i = 0; i < this.hidden; i++) {
            float value = this.h[i];
            if (value == 0.0F) {
                continue;
            }
            int row = i * vocabulary;
            for (int v = 0; v < vocabulary; v++) {
                this.logits[v] += value * w2Data[row + v];
            }
        }
        for (int v = 0; v < vocabulary; v++) {
            this.logits[v] += this.b2[v];
        }
        return this.logits;
    }

    /** One gradient step on one (context, next word) pair. Returns the loss. */
    public float trainSample(int[] ids, int target, float learningRate) {
        logits(ids);
        float[] probabilities = Tensor.softmax(this.logits, 1.0F);
        float loss = -(float) Math.log(Math.max(1.0E-9F, probabilities[target]));
        int vocabulary = this.b2.length;
        System.arraycopy(probabilities, 0, this.dLogits, 0, vocabulary);
        this.dLogits[target] -= 1.0F;
        if (target == Vocab.UNK) {
            // Never learn to predict the unknown token: it is noise, not language.
            for (int v = 0; v < vocabulary; v++) {
                this.dLogits[v] *= UNK_PENALTY;
            }
        }
        float[] w2Data = this.w2.data();
        java.util.Arrays.fill(this.dHidden, 0.0F);
        for (int i = 0; i < this.hidden; i++) {
            int row = i * vocabulary;
            float sum = 0.0F;
            for (int v = 0; v < vocabulary; v++) {
                sum += w2Data[row + v] * this.dLogits[v];
            }
            this.dHidden[i] = sum;
        }
        // Apply the output layer after reading it, so the gradient is consistent.
        for (int i = 0; i < this.hidden; i++) {
            int row = i * vocabulary;
            float value = this.h[i];
            for (int v = 0; v < vocabulary; v++) {
                w2Data[row + v] -= learningRate * value * this.dLogits[v];
            }
        }
        for (int v = 0; v < vocabulary; v++) {
            this.b2[v] -= learningRate * this.dLogits[v];
        }
        float[] w1Data = this.w1.data();
        java.util.Arrays.fill(this.dInput, 0.0F);
        for (int k = 0; k < this.x.length; k++) {
            int row = k * this.hidden;
            float sum = 0.0F;
            for (int i = 0; i < this.hidden; i++) {
                float dz = this.z[i] > 0.0F ? this.dHidden[i] : 0.0F;
                sum += w1Data[row + i] * dz;
                w1Data[row + i] -= learningRate * this.x[k] * dz;
            }
            this.dInput[k] = sum;
        }
        for (int i = 0; i < this.hidden; i++) {
            this.b1[i] -= learningRate * (this.z[i] > 0.0F ? this.dHidden[i] : 0.0F);
        }
        float[] embeddingData = this.embedding.data();
        for (int t = 0; t < this.context; t++) {
            int base = tokenAt(ids, t) * this.dim;
            int from = t * this.dim;
            for (int u = 0; u < this.dim; u++) {
                embeddingData[base + u] -= learningRate * this.dInput[from + u];
            }
        }
        this.samplesSeen++;
        return loss;
    }

    /**
     * Trains on a list of tokenised sentences.
     *
     * @return the average loss of the last epoch
     */
    public float trainCorpus(List<int[]> sentences, int epochs, float learningRate) {
        float average = 0.0F;
        for (int epoch = 0; epoch < epochs; epoch++) {
            double total = 0.0D;
            int count = 0;
            float rate = learningRate / (1.0F + epoch * 0.15F);
            for (int[] sentence : sentences) {
                for (int i = 0; i < sentence.length; i++) {
                    int[] prefix = new int[Math.min(i, this.context)];
                    System.arraycopy(sentence, Math.max(0, i - this.context), prefix, 0,
                            prefix.length);
                    total += trainSample(prefix, sentence[i], rate);
                    count++;
                }
            }
            average = count == 0 ? 0.0F : (float) (total / count);
        }
        return average;
    }

    /** Generates words until the end of sentence or the limit. */
    public String generate(int[] seed, int maxTokens, float temperature, Random random) {
        int[] window = new int[this.context];
        int start = Math.max(0, seed.length - this.context);
        for (int i = 0; i < this.context; i++) {
            int index = start + i;
            window[i] = index < seed.length ? seed[index] : Vocab.PAD;
        }
        StringBuilder out = new StringBuilder();
        int words = 0;
        for (int step = 0; step < maxTokens; step++) {
            float[] scores = logits(window);
            float[] probabilities = Tensor.softmax(scores, temperature);
            if (words < 2) {
                // A sentence needs a body: mask the end token until it has one,
                // otherwise a cold model answers with nothing at all.
                probabilities[Vocab.EOS] = 0.0F;
                probabilities[Vocab.PAD] = 0.0F;
                renormalize(probabilities);
            }
            int picked = sample(probabilities, random);
            if (picked == Vocab.EOS) {
                break;
            }
            if (picked == Vocab.PAD) {
                break;
            }
            String word = this.vocab.word(picked);
            if (word.isEmpty() || word.startsWith("<")) {
                // Advance the window anyway: repeating the same context would
                // spin forever picking the same unfixable token.
                System.arraycopy(window, 1, window, 0, this.context - 1);
                window[this.context - 1] = picked;
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(word);
            words++;
            System.arraycopy(window, 1, window, 0, this.context - 1);
            window[this.context - 1] = picked;
        }
        return out.toString();
    }

    private static void renormalize(float[] probabilities) {
        double total = 0.0D;
        for (float value : probabilities) {
            total += value;
        }
        if (total <= 0.0D) {
            return;
        }
        for (int i = 0; i < probabilities.length; i++) {
            probabilities[i] = (float) (probabilities[i] / total);
        }
    }

    /** Encodes a sentence into ids, ending with the end-of-sentence token. */
    public int[] encode(String sentence) {
        List<Integer> ids = new ArrayList<>();
        for (String token : tokenize(sentence)) {
            ids.add(this.vocab.id(token));
        }
        ids.add(Vocab.EOS);
        int[] out = new int[ids.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ids.get(i);
        }
        return out;
    }

    /** Splits on whitespace and pulls punctuation apart: "¿qué haces?" -> ¿ qué haces ? */
    public static List<String> tokenize(String sentence) {
        List<String> tokens = new ArrayList<>();
        if (sentence == null) {
            return tokens;
        }
        StringBuilder current = new StringBuilder();
        for (char c : sentence.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            if (Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else if ("¿?¡!,.;:()\"'".indexOf(c) >= 0) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                tokens.add(String.valueOf(c));
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static int sample(float[] probabilities, Random random) {
        double r = random.nextDouble();
        double cumulative = 0.0D;
        for (int i = 0; i < probabilities.length; i++) {
            cumulative += probabilities[i];
            if (r <= cumulative) {
                return i;
            }
        }
        return probabilities.length - 1;
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(FORMAT);
            out.writeInt(this.context);
            out.writeInt(this.dim);
            out.writeInt(this.hidden);
            out.writeLong(this.samplesSeen);
            this.vocab.save(out);
            this.embedding.save(out);
            this.w1.save(out);
            this.w2.save(out);
            for (float value : this.b1) {
                out.writeFloat(value);
            }
            for (float value : this.b2) {
                out.writeFloat(value);
            }
        }
    }

    public static TinyLm load(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            int format = in.readInt();
            if (format != FORMAT) {
                throw new IOException("unsupported model format " + format);
            }
            int context = in.readInt();
            int dim = in.readInt();
            int hidden = in.readInt();
            long seen = in.readLong();
            Vocab vocab = Vocab.load(in);
            TinyLm model = new TinyLm(vocab, context, dim, hidden, new Random(1L));
            Tensor loadedEmb = Tensor.load(in);
            Tensor loadedW1 = Tensor.load(in);
            Tensor loadedW2 = Tensor.load(in);
            model.replaceWeights(loadedEmb, loadedW1, loadedW2);
            for (int i = 0; i < model.b1.length; i++) {
                model.b1[i] = in.readFloat();
            }
            for (int i = 0; i < model.b2.length; i++) {
                model.b2[i] = in.readFloat();
            }
            model.samplesSeen = seen;
            return model;
        }
    }

    private void replaceWeights(Tensor newEmbedding, Tensor newW1, Tensor newW2) {
        // The checkpoint is the truth: copy through the existing instances.
        if (newEmbedding.rows() != this.embedding.rows() || newEmbedding.cols() != this.embedding.cols()
                || newW1.rows() != this.w1.rows() || newW1.cols() != this.w1.cols()
                || newW2.rows() != this.w2.rows() || newW2.cols() != this.w2.cols()) {
            throw new IllegalStateException("checkpoint shape does not match the model");
        }
        System.arraycopy(newEmbedding.data(), 0, this.embedding.data(), 0, this.embedding.size());
        System.arraycopy(newW1.data(), 0, this.w1.data(), 0, this.w1.size());
        System.arraycopy(newW2.data(), 0, this.w2.data(), 0, this.w2.size());
    }
}
