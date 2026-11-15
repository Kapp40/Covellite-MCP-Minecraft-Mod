package covellite.agent.nn;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Word vocabulary for the embedded language model.
 *
 * <p>Ids 0-2 are reserved: padding, end of sentence and unknown. Everything else
 * is learned from the corpus, so the model can only ever emit words it was
 * trained on. That is a feature: a companion that invents item ids is useless.</p>
 */
public final class Vocab {

    public static final int PAD = 0;
    public static final int EOS = 1;
    public static final int UNK = 2;

    private static final String PAD_TOKEN = "<pad>";
    private static final String EOS_TOKEN = "<eos>";
    private static final String UNK_TOKEN = "<unk>";

    private final List<String> words = new ArrayList<>();
    private final Map<String, Integer> ids = new HashMap<>();
    private final Map<String, Integer> counts = new HashMap<>();

    public Vocab() {
        this.words.add(PAD_TOKEN);
        this.words.add(EOS_TOKEN);
        this.words.add(UNK_TOKEN);
        this.ids.put(PAD_TOKEN, PAD);
        this.ids.put(EOS_TOKEN, EOS);
        this.ids.put(UNK_TOKEN, UNK);
    }

    /** Counts a token so {@link #freeze(int)} can keep the frequent ones. */
    public void count(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        this.counts.merge(token, 1, Integer::sum);
    }

    /** Keeps the {@code limit} most frequent tokens and orders the rest by count. */
    public void freeze(int limit) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(this.counts.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Integer> entry : entries) {
            if (this.words.size() >= limit) {
                break;
            }
            if (!this.ids.containsKey(entry.getKey())) {
                this.ids.put(entry.getKey(), this.words.size());
                this.words.add(entry.getKey());
            }
        }
    }

    /** Adds a token if there is room; returns its id (UNK when unknown). */
    public int addIfRoom(String token, int limit) {
        Integer known = this.ids.get(token);
        if (known != null) {
            return known;
        }
        if (this.words.size() >= limit) {
            return UNK;
        }
        this.ids.put(token, this.words.size());
        this.words.add(token);
        return this.words.size() - 1;
    }

    public int id(String token) {
        Integer id = this.ids.get(token);
        return id == null ? UNK : id;
    }

    public String word(int id) {
        return id >= 0 && id < this.words.size() ? this.words.get(id) : UNK_TOKEN;
    }

    public int size() {
        return this.words.size();
    }

    public List<String> words() {
        return List.copyOf(this.words);
    }

    public void save(DataOutputStream out) throws IOException {
        out.writeInt(this.words.size());
        for (String word : this.words) {
            byte[] bytes = word.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeShort(bytes.length);
            out.write(bytes);
        }
    }

    public static Vocab load(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size <= 0 || size > 1_000_000) {
            throw new IOException("corrupt vocabulary size: " + size);
        }
        Vocab vocab = new Vocab();
        for (int i = 0; i < size; i++) {
            int length = in.readUnsignedShort();
            byte[] bytes = new byte[length];
            in.readFully(bytes);
            String word = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            if (i < 3) {
                // Reserved ids are fixed by construction.
                continue;
            }
            vocab.ids.put(word, vocab.words.size());
            vocab.words.add(word);
        }
        return vocab;
    }
}
