package covellite.core;

import covellite.core.net.FrameChannel;
import covellite.core.net.FrameCodec;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.List;

public final class FrameTest {

    public static void run() throws IOException {
        testCodecBasic();
        testCodecPartial();
        testCodecEmpty();
        testCodecMaxSize();
        testChannelRoundTrip();
        System.out.println("  Frame: all tests passed");
    }

    private static void testCodecBasic() {
        FrameCodec codec = new FrameCodec();
        String json = "{\"hello\":\"world\"}";
        ByteBuffer encoded = FrameCodec.encode(json);
        List<String> frames = codec.feed(encoded);
        T.equal("basic frame count", 1, frames.size());
        T.equal("basic frame", json, frames.get(0));
    }

    private static void testCodecPartial() {
        FrameCodec codec = new FrameCodec();
        String json = "{\"a\":1}";
        ByteBuffer full = FrameCodec.encode(json);
        byte[] bytes = new byte[full.remaining()];
        full.get(bytes);

        // Split into tiny chunks
        for (int chunkSize : new int[]{1, 2, 3, 5}) {
            codec = new FrameCodec();
            List<String> frames = List.of();
            for (int i = 0; i < bytes.length; i += chunkSize) {
                int end = Math.min(i + chunkSize, bytes.length);
                ByteBuffer chunk = ByteBuffer.wrap(bytes, i, end - i);
                frames = codec.feed(chunk);
            }
            T.equal("partial chunkSize=" + chunkSize + " frame count", 1, frames.size());
            T.equal("partial chunkSize=" + chunkSize + " frame", json, frames.get(0));
        }
    }

    private static void testCodecEmpty() {
        FrameCodec codec = new FrameCodec();
        List<String> frames = codec.feed(FrameCodec.encode(""));
        T.equal("empty frame count", 1, frames.size());
        T.equal("empty frame", "", frames.get(0));
    }

    private static void testCodecMaxSize() {
        T.expectThrows("oversized payload", IllegalArgumentException.class, () -> {
            String big = "x".repeat(FrameCodec.MAX_FRAME_SIZE + 1);
            FrameCodec.encode(big);
        });
    }

    private static void testChannelRoundTrip() throws IOException {
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = server.socket().getLocalPort();

            InetSocketAddress address = (InetSocketAddress) server.getLocalAddress();
            try (SocketChannel client = SocketChannel.open(address)) {
                client.configureBlocking(true);
                try (SocketChannel accepted = server.accept()) {
                    accepted.configureBlocking(true);
                    FrameChannel a = new FrameChannel(accepted);
                    FrameChannel b = new FrameChannel(client);

                    String msg1 = "{\"id\":1}";
                    String msg2 = "{\"id\":2,\"data\":[true,false,null]}";
                    b.send(msg1);
                    b.send(msg2);
                    while (b.hasPendingWrites()) {
                        b.write();
                    }

                    List<String> received = List.of();
                    while (received.isEmpty()) {
                        received = a.read();
                    }
                    T.equal("channel roundtrip count", 2, received.size());
                    T.equal("channel roundtrip msg1", msg1, received.get(0));
                    T.equal("channel roundtrip msg2", msg2, received.get(1));
                }
            }
        }
    }
}
