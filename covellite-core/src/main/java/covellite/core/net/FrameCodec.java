package covellite.core.net;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Length-prefixed JSON framing over byte streams.
 * Frame layout: [4 bytes big-endian length][length bytes UTF-8 JSON].
 * Maximum frame size is 16 MiB to avoid unbounded memory use.
 */
public final class FrameCodec {
    public static final int MAX_FRAME_SIZE = 16 * 1024 * 1024;
    public static final int HEADER_LEN = 4;

    private final ByteBuffer header = ByteBuffer.allocate(HEADER_LEN);
    private ByteBuffer payload;
    private boolean readingHeader = true;

    /**
     * Feeds incoming bytes into the decoder. Returns a list of complete frames
     * decoded so far. The caller should pass every byte read from the channel.
     */
    public List<String> feed(ByteBuffer src) {
        List<String> frames = new ArrayList<>();
        while (src.hasRemaining()) {
            if (readingHeader) {
                int needed = HEADER_LEN - header.position();
                int take = Math.min(needed, src.remaining());
                for (int i = 0; i < take; i++) {
                    header.put(src.get());
                }
                if (header.position() < HEADER_LEN) {
                    break;
                }
                header.flip();
                int length = header.getInt() & 0xFFFFFFFF;
                header.clear();
                if (length < 0 || length > MAX_FRAME_SIZE) {
                    throw new IllegalArgumentException("Frame too large: " + length);
                }
                if (length == 0) {
                    frames.add("");
                    continue;
                }
                payload = ByteBuffer.allocate(length);
                readingHeader = false;
            } else {
                int take = Math.min(payload.remaining(), src.remaining());
                for (int i = 0; i < take; i++) {
                    payload.put(src.get());
                }
                if (payload.hasRemaining()) {
                    break;
                }
                payload.flip();
                String frame = StandardCharsets.UTF_8.decode(payload).toString();
                frames.add(frame);
                payload = null;
                readingHeader = true;
            }
        }
        return frames;
    }

    /**
     * Encodes a JSON string as a length-prefixed frame.
     */
    public static ByteBuffer encode(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FRAME_SIZE) {
            throw new IllegalArgumentException("Frame payload too large: " + bytes.length);
        }
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_LEN + bytes.length);
        buffer.putInt(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }

    public boolean isReadingHeader() {
        return readingHeader;
    }
}
