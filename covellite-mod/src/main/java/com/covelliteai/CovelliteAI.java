package com.covelliteai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.covelliteai.server.AgentBridge;
import com.covelliteai.server.BridgeApi;
import com.covelliteai.server.ChatLog;
import com.covelliteai.server.CompanionManager;
import com.covelliteai.server.CompanionCommands;
import com.covelliteai.server.PrototypeScenario;

import covellite.core.action.ActionQueue;
import covellite.core.dispatch.Dispatcher;
import covellite.core.event.EventLog;
import covellite.core.json.Json;
import covellite.core.net.BridgeServer;
import covellite.core.net.Handshake;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CoveMCP entry point.
 *
 * <p>The mod runs inside the player's own Minecraft process. When the
 * integrated server starts, it creates an artificial {@code ServerPlayer} in
 * that same world. There is no second client, no second account and no control
 * of the human player.</p>
 *
 * <p>This class is loaded on both the client and the dedicated server. It must
 * not reference client-only classes; any future visual perception code will
 * live in a separate client-only class and be loaded behind an explicit
 * distribution guard.</p>
 */
@Mod(CovelliteAI.MODID)
public final class CovelliteAI {
    public static final String MODID = "covemcp";
    public static final Logger LOGGER = LoggerFactory.getLogger("CoveMCP");

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Path TOKEN_FILE = Path.of(System.getProperty("user.home"),
            ".covemcp", "covemcp_token.txt");
    private static final Path LEGACY_TOKEN_FILE = Path.of(System.getProperty("user.home"),
            ".covelliteai", "covelliteai_token.txt");
    private static volatile CovelliteAI instance;

    private final CovelliteConfig config;
    private final ActionQueue actions;
    private final EventLog events;
    private final ChatLog chatLog;
    private final Dispatcher dispatcher = new Dispatcher();
    private final ConcurrentLinkedQueue<Map<String, Object>> inbound = new ConcurrentLinkedQueue<>();
    private final CompanionManager companion;
    private final BridgeApi bridgeApi;
    private final AgentBridge agentBridge;
    private final PrototypeScenario prototype;

    private volatile BridgeServer bridge;
    private volatile MinecraftServer server;
    private long nextBridgeAttempt;
    private long serverTicks;

    public CovelliteAI() {
        instance = this;
        this.config = CovelliteConfig.load(FMLPaths.CONFIGDIR.get());
        this.events = new EventLog(config.maxChatHistory);
        this.chatLog = new ChatLog(config.maxChatHistory);
        this.companion = new CompanionManager(config);
        this.bridgeApi = new BridgeApi(this.companion, this.chatLog, this.events);
        this.companion.setReviveHook(this.bridgeApi::onCompanionRevived);
        this.actions = new ActionQueue(config.maxActions, config.maxActionHistory,
                status -> { }, () -> this.companion.controller().stop());
        // In-process AI runtime. It shares the same dispatcher as the MCP bridge,
        // so an internal turn and an external agent execute identical tools, but
        // it never depends on OpenCode or any other external process.
        this.agentBridge = new AgentBridge(config, FMLPaths.GAMEDIR.get().resolve("covemcp"),
                FMLPaths.CONFIGDIR.get());

        Path prototypeMarker = FMLPaths.CONFIGDIR.get().resolve("covemcp-prototype-test.flag");
        this.prototype = Files.exists(prototypeMarker) ? new PrototypeScenario(this.companion) : null;
        if (this.prototype != null) {
            LOGGER.info("Prototype verification scenario enabled by {}", prototypeMarker);
        }

        registerHandlers();
        this.agentBridge.attach(this.dispatcher);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(this::onServerChat);
        NeoForge.EVENT_BUS.addListener(this::onLivingDeath);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            // Client-only: provides real POV screenshots through the renderer.
            com.covelliteai.client.PovCapture.register();
        }
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "CoveMCP-shutdown"));

        ensureBridge();
        LOGGER.info("CoveMCP server-side companion configured: playerName={}, bridgePort={}",
                config.playerName, config.port);
    }

    public static CovelliteAI instance() {
        return instance;
    }

    // ------------------------------------------------------------------
    // Server lifecycle
    // ------------------------------------------------------------------

    private void onServerStarted(ServerStartedEvent event) {
        this.server = event.getServer();
        this.companion.spawn(event.getServer());
        this.agentBridge.start(event.getServer(),
                event.getServer().getWorldData().getLevelName());
    }

    private void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer current = this.server;
        if (current == null) {
            return;
        }
        this.serverTicks++;
        if (this.serverTicks % 200L == 0L) {
            ensureBridge();
        }
        processInbound();
        this.agentBridge.tick(current);
        if (this.serverTicks % 100L == 0L) {
            this.agentBridge.updateSnapshot(snapshot());
        }
        this.actions.pump(System.currentTimeMillis());
        this.bridgeApi.tick(current);
        this.companion.tick(current);
        if (this.prototype != null) {
            this.prototype.tick(current);
        }
    }

    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            String name = player.getGameProfile().getName();
            this.events.add("player_joined", Map.of("name", name));
            if (this.companion.player() != player) {
                // Only humans wake the agent; the companion rejoining is not a user event.
                this.agentBridge.onPlayerJoin(name);
            }
            if (this.prototype != null && this.server != null) {
                this.prototype.onPlayerLogin(player, this.server);
            }
        }
    }

    private void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            this.events.add("player_left",
                    Map.of("name", player.getGameProfile().getName()));
        }
    }

    /**
     * Reads what players actually type. This is what makes the companion able
     * to answer the human player instead of only writing into the chat.
     */
    private void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        String name = player.getGameProfile().getName();
        String text = event.getRawText();
        boolean fromCompanion = this.companion.player() == player;
        this.chatLog.add(name, text, "chat", fromCompanion);
        this.events.add("chat", Map.of("sender", name, "message", text));
        if (!fromCompanion) {
            relayHumanChat(name, text);
            // Internal channel: the in-process agent hears the same line without
            // going through ChatRelay, which stays exclusive to MCP clients.
            this.agentBridge.onHumanChat(name, text);
        }
    }

    /**
     * ChatRelay: pushes ONE human-typed chat line to the bridge the instant it
     * happens, instead of waiting for the agent to poll (scan / get_recent_events
     * / the next tool call's new_chat). Cove's own lines never go through here
     * (fromCompanion is already filtered by the caller), and this is sent as an
     * id-less message, so it never collides with a pending request/response.
     */
    private void relayHumanChat(String sender, String text) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "chat_relay");
        message.put("from", sender);
        message.put("message", text);
        message.put("at", System.currentTimeMillis());
        send(message);
    }

    private void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        String cause = event.getSource().getLocalizedDeathMessage(player).getString();
        boolean fromCompanion = this.companion.player() == player;
        this.chatLog.add("server", cause, "system", fromCompanion);
        this.events.add("death",
                Map.of("name", player.getGameProfile().getName(), "message", cause));
    }

    private void onServerStopping(ServerStoppingEvent event) {
        this.companion.despawn(event.getServer());
        this.agentBridge.stop();
        this.bridgeApi.flushExploration();
    }

    private void onServerStopped(ServerStoppedEvent event) {
        // Defensive cleanup in case stopping was skipped by a crash path.
        this.companion.despawn(event.getServer());
        this.server = null;
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        CompanionCommands.register(event.getDispatcher(), this.companion, this.agentBridge,
                this.bridgeApi);
    }

    // ------------------------------------------------------------------
    // Bridge
    // ------------------------------------------------------------------

    private void ensureBridge() {
        if (bridge != null) return;
        long now = System.currentTimeMillis();
        if (now < nextBridgeAttempt) return;
        nextBridgeAttempt = now + 10_000L;
        try {
            String token = loadOrCreateToken();
            BridgeServer server = new BridgeServer(token, config.port,
                    inbound::add,
                    error -> LOGGER.warn("Bridge TCP error: {}", error.getMessage()));
            server.start();
            bridge = server;
            LOGGER.info("CoveMCP bridge listening on 127.0.0.1:{}", config.port);
        } catch (IOException e) {
            LOGGER.warn("Could not start bridge on 127.0.0.1:{} (will retry): {}", config.port, e.getMessage());
        }
    }

    private void shutdown() {
        BridgeServer b = bridge;
        if (b != null) b.close();
        this.agentBridge.close();
    }

    /** Compact world view fed to the agent every 5 seconds. */
    private Map<String, Object> snapshot() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("companion", this.companion.state());
        map.put("autopilot", this.bridgeApi.autopilotState());
        map.put("emergency_stop", this.bridgeApi.isEmergencyStopped());
        map.put("recent_events", this.events.recent(5));
        map.put("chat_messages", this.chatLog.size());
        return map;
    }

    private static String loadOrCreateToken() throws IOException {
        if (!Files.exists(TOKEN_FILE) && Files.exists(LEGACY_TOKEN_FILE)) {
            // Keep the existing bridge secret when upgrading from CovelliteAI.
            Files.createDirectories(TOKEN_FILE.getParent());
            Files.copy(LEGACY_TOKEN_FILE, TOKEN_FILE);
        }
        if (Files.exists(TOKEN_FILE)) {
            String token = Files.readString(TOKEN_FILE, StandardCharsets.UTF_8).trim();
            if (!token.isEmpty()) return token;
        }
        String token = Handshake.generateToken(SECURE_RANDOM);
        Files.createDirectories(TOKEN_FILE.getParent());
        Files.writeString(TOKEN_FILE, token, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(TOKEN_FILE,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows: ACLs apply; POSIX permissions are unavailable.
        }
        return token;
    }

    // ------------------------------------------------------------------
    // Request dispatch (always on the server thread)
    // ------------------------------------------------------------------

    private void processInbound() {
        int budget = 32;
        Map<String, Object> request;
        while (budget-- > 0 && (request = inbound.poll()) != null) {
            Map<String, Object> response;
            try {
                response = dispatcher.dispatch(request);
            } catch (RuntimeException e) {
                // Defense in depth: dispatch() already catches handler
                // exceptions, but a malformed request or a bug in a decorator
                // must never be able to kill the server tick that called us.
                LOGGER.error("Unhandled exception dispatching request {}: {}", request, e.toString(), e);
                Object rawId = request.get("id");
                Map<String, Object> error = new LinkedHashMap<>();
                if (rawId != null) {
                    error.put("id", String.valueOf(rawId));
                }
                error.put("error", Map.of("code", "internal_error",
                        "message", e.getMessage() == null ? e.toString() : e.getMessage()));
                response = error;
            }
            send(response);
        }
    }

    private void send(Map<String, Object> message) {
        BridgeServer b = bridge;
        if (b != null && b.isAuthenticated()) {
            b.send(Json.stringify(message));
        }
    }

    private void registerHandlers() {
        dispatcher.register("get_status", params -> status());
        this.bridgeApi.register(dispatcher);
    }

    private Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mod", MODID);
        map.put("version", "1.0.0-BetaReview3");
        map.put("architecture", "in_server_companion");
        map.put("side", FMLEnvironment.dist.name());
        map.put("server_running", this.server != null && this.server.isRunning());
        map.put("bridge_authenticated", this.bridge != null && this.bridge.isAuthenticated());
        map.put("bridge_port", this.config.port);
        map.put("emergency_stop_active", this.bridgeApi.isEmergencyStopped());
        map.put("live_actions", this.actions.liveStatuses().size());
        map.put("companion", this.companion.state());
        map.put("autopilot", this.bridgeApi.autopilotState());
        map.put("recent_events", this.events.recent(10).size());
        map.put("chat_messages", this.chatLog.size());
        map.put("agent", this.agentBridge.status());
        return map;
    }
}
