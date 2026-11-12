package covellite.bridge;

import covellite.core.dispatch.Dispatcher;
import covellite.core.json.Json;
import covellite.core.net.BridgeServer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end tests for the bridge:
 *
 * <ol>
 *   <li>Direct {@link ModConnection} correlation against a fake mod
 *       ({@link BridgeServer} + {@link Dispatcher}).</li>
 *   <li>Real MCP STDIO process launched from the installed distribution,
 *       talking to the same fake mod. This exercises the complete path
 *       MCP client -&gt; bridge process -&gt; authenticated TCP -&gt; mod
 *       dispatcher -&gt; response.</li>
 * </ol>
 *
 * <p>Plain Java main so the project stays dependency-light; wire it to the
 * {@code bridgeTest} Gradle task.</p>
 */
public final class BridgeIntegrationTest {
    private static final String TOKEN = "e2e-test-token-0123456789abcdef";
    private static final long MCP_TIMEOUT_MS = 20_000L;

    public static void main(String[] args) throws Exception {
        directConnectionTest();
        mcpStdioTest();
        System.out.println("  BridgeIntegration: all tests passed");
    }

    // ------------------------------------------------------------------
    // 1. ModConnection <-> fake mod
    // ------------------------------------------------------------------

    private static void directConnectionTest() throws Exception {
        int port = 17471;
        AtomicReference<BridgeServer> serverRef = new AtomicReference<>();
        List<Throwable> serverErrors = new ArrayList<>();
        Dispatcher dispatcher = fakeModDispatcher();

        BridgeServer server = new BridgeServer(TOKEN, port,
                request -> {
                    BridgeServer s = serverRef.get();
                    if (s != null) s.send(Json.stringify(dispatcher.dispatch(request)));
                },
                error -> serverErrors.add(error));
        serverRef.set(server);
        server.start();

        ModConnection connection = new ModConnection(TOKEN, port);
        try {
            connection.connect();
            check("direct: authenticated", connection.isAuthenticated());

            // Basic request/response.
            Object echo = connection.call("echo", Map.of("value", 7));
            check("direct: echo result is map", echo instanceof Map);
            Map<?, ?> echoMap = (Map<?, ?>) ((Map<?, ?>) echo).get("echo");
            check("direct: echo value", echoMap != null && "7".equals(String.valueOf(echoMap.get("value"))));

            // Concurrent calls must each receive their own correlated response.
            ExecutorService pool = Executors.newFixedThreadPool(8);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                final int n = i;
                futures.add(pool.submit(() -> {
                    Map<?, ?> result = (Map<?, ?>) connection.call("echo", Map.of("seq", n));
                    Map<?, ?> inner = (Map<?, ?>) result.get("echo");
                    if (inner == null || !String.valueOf(n).equals(String.valueOf(inner.get("seq")))) {
                        throw new AssertionError("correlation mismatch for " + n + ": " + result);
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);
            pool.shutdownNow();
            check("direct: 40 concurrent calls correlated", true);

            // Async actions: callAndWait must poll locally until the action ends.
            Map<?, ?> waited = (Map<?, ?>) connection.callAndWait("goto", Map.of("x", 1), 5_000L);
            check("direct: callAndWait completes", "completed".equals(waited.get("state")));
            check("direct: callAndWait drops id/method",
                    !waited.containsKey("id") && !waited.containsKey("method"));
            check("direct: callAndWait keeps final position", waited.get("at") != null);
            Map<?, ?> instant = (Map<?, ?>) connection.callAndWait("echo", Map.of("v", 1), 5_000L);
            check("direct: callAndWait passes instant results through", instant.get("echo") != null);

            // Dispatcher error mapping.
            try {
                connection.call("fail", Map.of());
                throw new AssertionError("expected invalid_params error");
            } catch (ModConnection.ModException e) {
                check("direct: invalid_params code", "invalid_params".equals(e.code()));
            }

            try {
                connection.call("no_such_method", Map.of());
                throw new AssertionError("expected unknown_method error");
            } catch (ModConnection.ModException e) {
                check("direct: unknown_method code", "unknown_method".equals(e.code()));
            }

            // Timeout handling.
            long start = System.currentTimeMillis();
            try {
                connection.call("slow", Map.of(), 150);
                throw new AssertionError("expected timeout");
            } catch (ModConnection.ModException e) {
                long elapsed = System.currentTimeMillis() - start;
                check("direct: timeout code", "timeout".equals(e.code()));
                check("direct: timeout bounded (" + elapsed + " ms)", elapsed < 3000);
            }
            Thread.sleep(600); // let the late response drain

            connection.close();
            try {
                connection.call("echo", Map.of());
                throw new AssertionError("expected mod_not_connected");
            } catch (ModConnection.ModException e) {
                check("direct: disconnected code", "mod_not_connected".equals(e.code()));
            }
        } finally {
            connection.close();
            server.close();
        }
        check("direct: no server errors " + serverErrors, serverErrors.isEmpty());
    }

    // ------------------------------------------------------------------
    // 2. Real MCP STDIO process <-> fake mod
    // ------------------------------------------------------------------

    private static void mcpStdioTest() throws Exception {
        int port = 17472;
        AtomicReference<BridgeServer> serverRef = new AtomicReference<>();
        List<Throwable> serverErrors = new ArrayList<>();
        Dispatcher dispatcher = fakeModDispatcher();

        BridgeServer server = new BridgeServer(TOKEN, port,
                request -> {
                    BridgeServer s = serverRef.get();
                    if (s != null) s.send(Json.stringify(dispatcher.dispatch(request)));
                },
                error -> serverErrors.add(error));
        serverRef.set(server);
        server.start();

        Path work = Path.of("").toAbsolutePath();
        Path tokenFile = Files.createTempFile("covellite-e2e-token", ".txt");
        Files.writeString(tokenFile, TOKEN, StandardCharsets.UTF_8);

        Path libDir = work.resolve("build/install/covellite-bridge/lib");
        check("mcp: installed lib directory exists (" + libDir + ")", Files.isDirectory(libDir));

        String javaExe = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        if (!Files.exists(Path.of(javaExe))) {
            javaExe = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        }

        ProcessBuilder pb = new ProcessBuilder(
                javaExe,
                "-cp", libDir + java.io.File.separator + "*",
                "covellite.bridge.BridgeMain",
                "--token-file", tokenFile.toString(),
                "--port", String.valueOf(port));
        pb.redirectErrorStream(false);

        Process process = pb.start();
        BlockingQueue<String> stdout = new LinkedBlockingQueue<>();
        StringBuilder stderr = new StringBuilder();
        Thread outReader = drain(process.getInputStream(), stdout, null);
        Thread errReader = drain(process.getErrorStream(), null, stderr);

        BufferedWriter stdin = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        try {
            send(stdin, Map.of(
                    "jsonrpc", "2.0",
                    "id", 1,
                    "method", "initialize",
                    "params", Map.of(
                            "protocolVersion", "2024-11-05",
                            "capabilities", Map.of(),
                            "clientInfo", Map.of("name", "covellite-e2e", "version", "1.0"))));

            Map<String, Object> init = readResponse(stdout, 1, MCP_TIMEOUT_MS);
            Map<?, ?> initResult = (Map<?, ?>) init.get("result");
            Map<?, ?> serverInfo = initResult == null ? null : (Map<?, ?>) initResult.get("serverInfo");
            check("mcp: initialize returns serverInfo",
                    serverInfo != null && "covemcp".equals(serverInfo.get("name")));

            send(stdin, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));

            // Real tool call through MCP -> bridge -> TCP -> fake mod -> back.
            send(stdin, Map.of(
                    "jsonrpc", "2.0",
                    "id", 2,
                    "method", "tools/call",
                    "params", Map.of("name", "get_status", "arguments", Map.of())));
            Map<String, Object> call = readResponse(stdout, 2, MCP_TIMEOUT_MS);
            Map<String, Object> payload = toolText(call);
            check("mcp: get_status ai_player", Boolean.TRUE.equals(payload.get("ai_player")));
            check("mcp: get_status connected", Boolean.TRUE.equals(payload.get("connected")));

            send(stdin, Map.of(
                    "jsonrpc", "2.0",
                    "id", 3,
                    "method", "tools/call",
                    "params", Map.of("name", "get_block_info",
                            "arguments", Map.of("x", 1, "y", 2, "z", 3))));
            Map<String, Object> echoCall = readResponse(stdout, 3, MCP_TIMEOUT_MS);
            Map<String, Object> echoPayload = toolText(echoCall);
            Map<?, ?> echoInner = (Map<?, ?>) echoPayload.get("echo");
            check("mcp: arguments round-trip",
                    echoInner != null && "2".equals(String.valueOf(echoInner.get("y"))));

            // Handler failure must surface as an MCP tool error, not a crash.
            send(stdin, Map.of(
                    "jsonrpc", "2.0",
                    "id", 4,
                    "method", "tools/call",
                    "params", Map.of("name", "get_targeted_object", "arguments", Map.of())));
            Map<String, Object> failCall = readResponse(stdout, 4, MCP_TIMEOUT_MS);
            Map<?, ?> failResult = (Map<?, ?>) failCall.get("result");
            check("mcp: error result flagged", failResult != null
                    && Boolean.TRUE.equals(failResult.get("isError")));
            String failText = firstText(failResult);
            check("mcp: error text has code (" + failText + ")",
                    failText != null && failText.startsWith("invalid_params"));

            check("mcp: process still alive", process.isAlive());
        } finally {
            try {
                stdin.close();
            } catch (IOException ignored) {}
            boolean exited = process.waitFor(10, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            outReader.join(1000);
            errReader.join(1000);
            server.close();
            Files.deleteIfExists(tokenFile);
            List<Throwable> unexpected = new ArrayList<>();
            for (Throwable t : serverErrors) {
                // The bridge exits at the end of the test; a reset is expected.
                if (!(t instanceof java.net.SocketException)) unexpected.add(t);
            }
            if (!unexpected.isEmpty()) {
                throw new AssertionError("mcp: server errors " + unexpected);
            }
            if (!process.isAlive() && process.exitValue() != 0 && process.exitValue() != 143) {
                System.out.println("  [mcp] bridge stderr:\n" + stderr);
                throw new AssertionError("mcp: bridge exited with " + process.exitValue());
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Dispatcher fakeModDispatcher() {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.register("get_status", p -> {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("mod", "covemcp");
            status.put("ai_player", true);
            status.put("connected", true);
            status.put("bridge_authenticated", true);
            return status;
        });
        dispatcher.register("echo", p -> Map.of("echo", p));
        dispatcher.register("fail", p -> {
            throw new IllegalArgumentException("bad params");
        });
        dispatcher.register("get_block_info", p -> Map.of("echo", p));
        dispatcher.register("get_targeted_object", p -> {
            throw new IllegalArgumentException("bad params");
        });
        java.util.concurrent.atomic.AtomicInteger polls = new java.util.concurrent.atomic.AtomicInteger();
        dispatcher.register("goto", p -> {
            polls.set(0);
            return Map.of("action_id", "act-1", "state", "running");
        });
        dispatcher.register("get_action_status", p -> {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("id", "act-1");
            status.put("method", "goto");
            status.put("state", polls.incrementAndGet() < 4 ? "running" : "completed");
            status.put("at", List.of(1.0D, 2.0D, 3.0D));
            return status;
        });
        dispatcher.register("slow", p -> {
            Thread.sleep(400);
            return Map.of("late", true);
        });
        return dispatcher;
    }

    private static void send(BufferedWriter writer, Map<String, Object> message) throws IOException {
        writer.write(Json.stringify(message));
        writer.write('\n');
        writer.flush();
    }

    private static Map<String, Object> readResponse(BlockingQueue<String> lines, Object id,
                                                    long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) throw new AssertionError("timed out waiting for response id " + id);
            String line = lines.poll(remaining, TimeUnit.MILLISECONDS);
            if (line == null) continue;
            if (line.isBlank()) continue;
            Map<String, Object> parsed;
            try {
                parsed = Json.parseObject(line);
            } catch (Exception e) {
                throw new AssertionError("non-JSON stdout line: " + line);
            }
            if (parsed.containsKey("id") && String.valueOf(id).equals(String.valueOf(parsed.get("id")))) {
                return parsed;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toolText(Map<String, Object> response) {
        Map<?, ?> result = (Map<?, ?>) response.get("result");
        if (result == null) throw new AssertionError("missing result: " + response);
        String text = firstText(result);
        if (text == null) throw new AssertionError("missing tool text: " + response);
        return Json.parseObject(text);
    }

    private static String firstText(Map<?, ?> result) {
        Object content = result.get("content");
        if (!(content instanceof List<?> list) || list.isEmpty()) return null;
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> map)) return null;
        Object text = map.get("text");
        return text == null ? null : String.valueOf(text);
    }

    private static Thread drain(java.io.InputStream stream, BlockingQueue<String> lines,
                                StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (lines != null) lines.add(line);
                    if (sink != null) {
                        synchronized (sink) {
                            sink.append(line).append('\n');
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }, "e2e-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void check(String name, boolean condition) {
        if (!condition) throw new AssertionError("FAILED: " + name);
        System.out.println("  ok: " + name);
    }
}
