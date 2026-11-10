package covellite.core.net;

import covellite.core.json.Json;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * TCP client that connects to the mod's bridge server. Performs the
 * challenge-response handshake and exchanges length-prefixed JSON frames.
 */
public final class BridgeClient {
    private final String token;
    private final int port;
    private final Consumer<Map<String, Object>> messageHandler;
    private final Consumer<Throwable> errorHandler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ConcurrentLinkedQueue<String> outbound = new ConcurrentLinkedQueue<>();

    private Thread selectorThread;
    private Selector selector;
    private volatile FrameChannel channel;
    private volatile boolean authenticated = false;

    public BridgeClient(String token, int port,
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
        SocketChannel socket = SocketChannel.open();
        socket.configureBlocking(false);
        socket.connect(new InetSocketAddress("127.0.0.1", port));
        channel = new FrameChannel(socket);
        socket.register(selector, SelectionKey.OP_CONNECT);

        selectorThread = new Thread(this::runLoop, "BridgeClient");
        selectorThread.setDaemon(true);
        selectorThread.start();
    }

    public void send(String json) {
        outbound.add(json);
        if (channel != null && channel.isOpen() && selector != null) {
            try {
                SelectionKey key = channel.channel().keyFor(selector);
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
                        if (key.isConnectable()) {
                            finishConnect(key);
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

    private void finishConnect(SelectionKey key) throws IOException {
        SocketChannel socket = (SocketChannel) key.channel();
        if (socket.finishConnect()) {
            key.interestOps(SelectionKey.OP_READ);
        }
    }

    private void read(SelectionKey key) throws IOException {
        if (channel == null) return;
        List<String> frames = channel.read();
        for (String frame : frames) {
            if (!authenticated) {
                if (frame.length() == 64) {
                    String response = Handshake.computeResponse(token, frame);
                    channel.send(response);
                    key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                } else {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> result = Json.parseObject(frame);
                        if (Boolean.TRUE.equals(result.get("ok"))) {
                            authenticated = true;
                            key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                        } else {
                            errorHandler.accept(new AuthException("Authentication failed: " + result.get("error")));
                            closeKey(key);
                        }
                    } catch (Exception e) {
                        errorHandler.accept(new AuthException("Invalid handshake response: " + frame, e));
                        closeKey(key);
                    }
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
        if (!channel.isOpen()) {
            closeKey(key);
        }
    }

    private void write(SelectionKey key) throws IOException {
        if (channel == null || !channel.isOpen()) {
            closeKey(key);
            return;
        }
        processOutbound();
        boolean empty = channel.write();
        if (empty) {
            key.interestOps(SelectionKey.OP_READ);
        }
    }

    private void processOutbound() {
        if (channel == null || !channel.isOpen()) return;
        String json;
        while ((json = outbound.poll()) != null) {
            channel.send(json);
        }
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
        channel = null;
    }

    private void cleanup() {
        try {
            if (selector != null) selector.close();
        } catch (IOException ignored) {}
        authenticated = false;
        channel = null;
    }
}
