package covellite.bridge;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * MCP server over STDIO. Protocol framing, initialization and tool discovery
 * are handled by the official MCP Java SDK. Each tool call is forwarded to the
 * mod over authenticated localhost TCP and correlated by request id.
 *
 * <p>stdout is reserved for MCP JSON-RPC. All diagnostics go to stderr.</p>
 */
public final class BridgeMain {
    public static void main(String[] args) {
        Path tokenFile = Path.of(System.getProperty("user.home"),
                ".covemcp", "covemcp_token.txt");
        Path legacyTokenFile = Path.of(System.getProperty("user.home"),
                ".covelliteai", "covelliteai_token.txt");
        int port = Integer.getInteger("covemcp.port", 17452);

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--token-file" -> {
                    if (i + 1 >= args.length) usage("--token-file requires a path");
                    tokenFile = Path.of(args[++i]);
                }
                case "--port" -> {
                    if (i + 1 >= args.length) usage("--port requires a number");
                    try {
                        port = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        usage("invalid port: " + args[i]);
                    }
                }
                case "--help", "-h" -> {
                    System.out.println("CoveMCP's MCP bridge");
                    System.out.println("  --token-file <path>  shared HMAC token (default ~/.covemcp/covemcp_token.txt)");
                    System.out.println("  --port <n>           mod TCP port (default 17452)");
                    return;
                }
                default -> usage("unknown argument: " + args[i]);
            }
        }

        String token;
        try {
            Path readable = Files.exists(tokenFile) ? tokenFile
                    : (Files.exists(legacyTokenFile) ? legacyTokenFile : tokenFile);
            token = Files.readString(readable, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            System.err.println("[bridge] cannot read token file " + tokenFile + ": " + e.getMessage());
            System.err.println("[bridge] start the Minecraft client with CoveMCP once to generate it");
            System.exit(1);
            return;
        }
        if (token.isEmpty()) {
            System.err.println("[bridge] token file is empty: " + tokenFile);
            System.exit(1);
            return;
        }

        ChatRelay relay = new ChatRelay();
        ModConnection connection = new ModConnection(token, port, relay);
        Runtime.getRuntime().addShutdownHook(new Thread(connection::close, "bridge-shutdown"));

        // The MCP client may launch the bridge before Minecraft finishes loading.
        // Connect in the background so the MCP initialize handshake is never
        // delayed by the game.
        Thread connector = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    if (!connection.isAuthenticated()) {
                        connection.connect();
                    }
                    Thread.sleep(2000L);
                } catch (IOException e) {
                    try {
                        Thread.sleep(2000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "bridge-connector");
        connector.setDaemon(true);
        connector.start();

        List<io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification> tools =
                ToolCatalog.build(connection, relay);

        StdioServerTransportProvider transport =
                new StdioServerTransportProvider(McpJsonDefaults.getMapper());

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("covemcp", "1.0.0-BetaReview3")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(tools)
                .build();

        System.err.println("[bridge] MCP server ready with " + tools.size() + " tools");

        // The SDK's STDIO threads keep the process alive. Close cleanly if the
        // transport reaches EOF and the JVM begins shutting down.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.closeGracefully();
            } catch (Exception ignored) {
                server.close();
            }
        }, "bridge-mcp-shutdown"));
    }

    private static void usage(String message) {
        System.err.println("[bridge] " + message);
        System.err.println("[bridge] usage: --token-file <path> --port <n>");
        System.exit(2);
    }
}
