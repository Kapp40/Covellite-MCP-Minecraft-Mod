package covellite.agent.nn;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * A dense float tensor with the handful of operations this project needs.
 *
 * <p>Pure Java on purpose: no native libraries, no framework, no network. The
 * same class trains the model on a desktop and runs it inside the game, so a
 * checkpoint that loads here behaves exactly like the one that was trained.</p>
 */
public final class Tensor {

    private final int rows;
    private final int cols;
    private final float[] data;

    public Tensor(int rows, int cols) {
        if (rows <= 0 || cols <= 0) {
            throw new IllegalArgumentException("tensor shape must be positive");
        }
        this.rows = rows;
        this.cols = cols;
        this.data = new float[rows * cols];
    }

    public Tensor(int rows, int cols, float[] values) {
        if (values.length != rows * cols) {
            throw new IllegalArgumentException("expected " + (rows * cols) + " values, got "
                    + values.length);
        }
        this.rows = rows;
        this.cols = cols;
        this.data = values;
    }

    /** Uniform in [-scale, scale]: the usual small init for a dense layer. */
    public static Tensor random(int rows, int cols, float scale, Random random) {
        Tensor tensor = new Tensor(rows, cols);
        for (int i = 0; i < tensor.data.length; i++) {
            tensor.data[i] = (random.nextFloat() * 2.0F - 1.0F) * scale;
        }
        return tensor;
    }

    public static Tensor identity(int size) {
        Tensor tensor = new Tensor(size, size);
        for (int i = 0; i < size; i++) {
            tensor.data[i * size + i] = 1.0F;
        }
        return tensor;
    }

    public int rows() {
        return this.rows;
    }

    public int cols() {
        return this.cols;
    }

    public int size() {
        return this.data.length;
    }

    /** Backing array; hot loops use it directly to avoid bounds checks. */
    public float[] data() {
        return this.data;
    }

    public float get(int row, int col) {
        return this.data[row * this.cols + col];
    }

    public float set(int row, int col, float value) {
        this.data[row * this.cols + col] = value;
        return value;
    }

    public void add(int row, int col, float delta) {
        this.data[row * this.cols + col] += delta;
    }

    public void zero() {
        java.util.Arrays.fill(this.data, 0.0F);
    }

    /** Matrix product: (rows x cols) * (cols x other.cols). */
    public Tensor matmul(Tensor other) {
        if (this.cols != other.rows) {
            throw new IllegalArgumentException("cannot multiply " + this.rows + "x" + this.cols
                    + " by " + other.rows + "x" + other.cols);
        }
        Tensor out = new Tensor(this.rows, other.cols);
        float[] a = this.data;
        float[] b = other.data;
        float[] c = out.data;
        int inner = this.cols;
        int width = other.cols;
        // ikj order: the row of `b` stays in cache while the whole output row fills.
        for (int i = 0; i < this.rows; i++) {
            int aRow = i * inner;
            int cRow = i * width;
            for (int k = 0; k < inner; k++) {
                float factor = a[aRow + k];
                if (factor == 0.0F) {
                    continue;
                }
                int bRow = k * width;
                for (int j = 0; j < width; j++) {
                    c[cRow + j] += factor * b[bRow + j];
                }
            }
        }
        return out;
    }

    /** Adds one value per column (a bias vector). */
    public Tensor addBias(float[] bias) {
        if (bias.length != this.cols) {
            throw new IllegalArgumentException("bias length " + bias.length
                    + " does not match " + this.cols + " columns");
        }
        for (int i = 0; i < this.rows; i++) {
            int row = i * this.cols;
            for (int j = 0; j < this.cols; j++) {
                this.data[row + j] += bias[j];
            }
        }
        return this;
    }

    public Tensor relu() {
        for (int i = 0; i < this.data.length; i++) {
            if (this.data[i] < 0.0F) {
                this.data[i] = 0.0F;
            }
        }
        return this;
    }

    public Tensor scaled(float factor) {
        for (int i = 0; i < this.data.length; i++) {
            this.data[i] *= factor;
        }
        return this;
    }

    public Tensor copy() {
        return new Tensor(this.rows, this.cols, this.data.clone());
    }

    /** Softmax over the whole array, with temperature for sampling. */
    public static float[] softmax(float[] logits, float temperature) {
        float scale = temperature <= 0.0F ? 1.0F : 1.0F / temperature;
        float max = Float.NEGATIVE_INFINITY;
        for (float value : logits) {
            float scaled = value * scale;
            if (scaled > max) {
                max = scaled;
            }
        }
        double total = 0.0D;
        float[] out = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            double e = Math.exp(logits[i] * scale - max);
            out[i] = (float) e;
            total += e;
        }
        if (total <= 0.0D || Double.isNaN(total)) {
            java.util.Arrays.fill(out, 1.0F / logits.length);
            return out;
        }
        for (int i = 0; i < out.length; i++) {
            out[i] = (float) (out[i] / total);
        }
        return out;
    }

    public void save(DataOutputStream out) throws IOException {
        out.writeInt(this.rows);
        out.writeInt(this.cols);
        for (float value : this.data) {
            out.writeFloat(value);
        }
    }

    public static Tensor load(DataInputStream in) throws IOException {
        int rows = in.readInt();
        int cols = in.readInt();
        if (rows <= 0 || cols <= 0 || rows > 1_000_000 || cols > 1_000_000) {
            throw new IOException("corrupt tensor header: " + rows + "x" + cols);
        }
        Tensor tensor = new Tensor(rows, cols);
        for (int i = 0; i < tensor.data.length; i++) {
            tensor.data[i] = in.readFloat();
        }
        return tensor;
    }
}
