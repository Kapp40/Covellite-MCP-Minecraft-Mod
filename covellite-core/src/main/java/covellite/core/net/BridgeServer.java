package covellite.core.net;

import covellite.core.json.Json;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * TCP server bound to localhost. Accepts a single bridge connection, performs
 * HMAC challenge-response authentication, then exchanges length-prefixed JSON
 * frames.
 *
 * <p>Threading: start() launches a selector thread. send() and close() may be
 * called from any thread.</p>
 */
public final class BridgeServer {
    private static final int MAX_OUTBOUND_FRAMES = 4096;

    private final String token;
    private final int port;
    private final Consumer<Map<String, Object>> messageHandler;
    private final Consumer<Throwable> errorHandler;
    private final SecureRandom random = new SecureRandom();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ConcurrentLinkedQueue<String> outbound = new ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicInteger outboundSize =
            new java.util.concurrent.atomic.AtomicInteger();

    private Thread selectorThread;
    private ServerSocketChannel serverChannel;
    private Selector selector;
    private volatile FrameChannel client;
    private volatile String challenge;
    private volatile boolean authenticated = false;

    public BridgeServer(String token, int port,
                        Consumer<Map<String, Object>> messageHandler,
                        Consumer<Throwable> errorHandler) {
        this.token = Objects.requireNonNull(token);
        this.port = port;
        this.messageHandler = Objects.requireNonNull(messageHandler);
        this.errorHandler = Objects.requireNonNull(errorHandler);
    }

    public synchronized void start() throws IOException {
        if (running.get()) return;
        running.set(true);

        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress("127.0.0.1", port));
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        selectorThread = new Thread(this::runLoop, "BridgeServer");
        selectorThread.setDaemon(true);
        selectorThread.start();
    }

    public void send(String json) {
        if (outboundSize.incrementAndGet() > MAX_OUTBOUND_FRAMES) {
            outboundSize.decrementAndGet();
            errorHandler.accept(new IllegalStateException("outbound frame queue is full"));
            return;
        }
        outbound.add(json);
        if (client != null && client.isOpen() && selector != null) {
            try {
                SelectionKey key = client.channel().keyFor(selector);
                if (key != null && key.isValid()) {
                    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                }
            } catch (Exception ignored) {}
            wakeup();
        }
    }

    public void close() {
        running.set(false);
        wakeup();
        try {
            if (selectorThread != null) {
                selectorThread.join(2000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cleanup();
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    private void runLoop() {
        try {
            while (running.get()) {
                selector.select(100);
                processOutbound();
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    try {
                        if (key.isAcceptable()) {
                            accept();
                        } else if (key.isReadable()) {
                            read(key);
                        } else if (key.isWritable()) {
                            write(key);
                        }
                    } catch (IOException e) {
                        errorHandler.accept(e);
                        closeKey(key);
                    }
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                errorHandler.accept(e);
            }
        } finally {
            cleanup();
        }
    }

    private void accept() throws IOException {
        SocketChannel channel = serverChannel.accept();
        if (client != null) {
            channel.close();
            return;
        }
        channel.configureBlocking(false);
        client = new FrameChannel(channel);
        SelectionKey key = channel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        challenge = Handshake.generateChallenge(random);
        client.send(challenge);
        key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        wakeup();
    }

    private void read(SelectionKey key) throws IOException {
        if (client == null) return;
        List<String> frames = client.read();
        for (String frame : frames) {
            if (!authenticated) {
                if (verifyHandshake(frame)) {
                    authenticated = true;
                    client.send(Json.stringify(Handshake.ok()));
                    key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                } else {
                    client.send(Json.stringify(Handshake.fail("invalid_auth")));
                    closeKey(key);
                }
            } else {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> message = Json.parseObject(frame);
                    messageHandler.accept(message);
                } catch (Exception e) {
                    errorHandler.accept(new RuntimeException("Invalid JSON frame: " + frame, e));
                }
            }
        }
        if (!client.isOpen()) {
            closeKey(key);
        }
    }

    private void write(SelectionKey key) throws IOException {
        if (client == null || !client.isOpen()) {
            closeKey(key);
            return;
        }
        processOutbound();
        boolean empty = client.write();
        if (empty) {
            key.interestOps(SelectionKey.OP_READ);
        }
    }

    private void processOutbound() {
        if (client == null) return;
        String json;
        while ((json = outbound.poll()) != null) {
            outboundSize.decrementAndGet();
            if (authenticated && client.isOpen()) {
                client.send(json);
            }
        }
    }

    private boolean verifyHandshake(String frame) {
        if (challenge == null) return false;
        return Handshake.verify(token, challenge, frame);
    }

    private void wakeup() {
        if (selector != null) {
            selector.wakeup();
        }
    }

    private void closeKey(SelectionKey key) {
        try {
            key.channel().close();
        } catch (IOException ignored) {}
        key.cancel();
        authenticated = false;
        client = null;
        challenge = null;
        outbound.clear();
        outboundSize.set(0);
    }

    private void cleanup() {
        try {
            if (serverChannel != null) serverChannel.close();
            if (selector != null) selector.close();
        } catch (IOException ignored) {}
        authenticated = false;
        client = null;
        outbound.clear();
        outboundSize.set(0);
    }
}
