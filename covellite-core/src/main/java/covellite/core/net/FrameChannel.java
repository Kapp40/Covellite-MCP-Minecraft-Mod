package covellite.core.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Non-blocking frame channel. Handles partial reads/writes and keeps a small
 * outbound queue. Not thread-safe on its own; callers should synchronize with
 * the selector/loop that owns the channel.
 */
public final class FrameChannel {
    private final SocketChannel channel;
    private final FrameCodec decoder = new FrameCodec();
    private final ByteBuffer readBuffer = ByteBuffer.allocate(8192);
    private final Deque<ByteBuffer> writeQueue = new ArrayDeque<>();
    private boolean closed = false;

    public FrameChannel(SocketChannel channel) {
        this.channel = channel;
    }

    /**
     * Reads from the channel and returns any complete frames. Must be called
     * when the channel is readable.
     */
    public List<String> read() throws IOException {
        if (closed) {
            throw new IOException("Channel closed");
        }
        readBuffer.clear();
        int read = channel.read(readBuffer);
        if (read < 0) {
            close();
            return List.of();
        }
        if (read == 0) {
            return List.of();
        }
        readBuffer.flip();
        return decoder.feed(readBuffer);
    }

    /**
     * Queues a JSON message for writing. Call write() when the channel is writable.
     */
    public void send(String json) {
        if (closed) {
            throw new IllegalStateException("Channel closed");
        }
        writeQueue.add(FrameCodec.encode(json));
    }

    /**
     * Drains the outbound queue. Returns true if the queue is empty.
     */
    public boolean write() throws IOException {
        while (!writeQueue.isEmpty()) {
            ByteBuffer buffer = writeQueue.peek();
            int written = channel.write(buffer);
            if (written == 0) {
                return false;
            }
            if (!buffer.hasRemaining()) {
                writeQueue.poll();
            }
        }
        return true;
    }

    public boolean hasPendingWrites() {
        return !writeQueue.isEmpty();
    }

    public boolean isOpen() {
        return channel.isOpen() && !closed;
    }

    public void close() throws IOException {
        closed = true;
        channel.close();
    }

    public SocketChannel channel() {
        return channel;
    }
}
