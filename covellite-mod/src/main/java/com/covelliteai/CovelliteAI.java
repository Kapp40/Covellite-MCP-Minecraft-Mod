package com.covelliteai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.covelliteai.server.BridgeApi;
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
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CovelliteAI entry point.
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
    public static final String MODID = "covelliteai";
    public static final Logger LOGGER = LoggerFactory.getLogger("CovelliteAI");

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Path TOKEN_FILE = Path.of(System.getProperty("user.home"),
            ".covelliteai", "covelliteai_token.txt");
    private static volatile CovelliteAI instance;

    private final CovelliteConfig config;
    private final ActionQueue actions;
    private final EventLog events;
    private final Dispatcher dispatcher = new Dispatcher();
    private final ConcurrentLinkedQueue<Map<String, Object>> inbound = new ConcurrentLinkedQueue<>();
    private final CompanionManager companion;
    private final BridgeApi bridgeApi;
    private final PrototypeScenario prototype;

    private volatile BridgeServer bridge;
    private volatile MinecraftServer server;
    private long nextBridgeAttempt;
    private long serverTicks;

    public CovelliteAI() {
        instance = this;
        this.config = CovelliteConfig.load(FMLPaths.CONFIGDIR.get());
        this.events = new EventLog(config.maxChatHistory);
        this.companion = new CompanionManager(config);
        this.bridgeApi = new BridgeApi(this.companion);
        this.actions = new ActionQueue(config.maxActions, config.maxActionHistory,
                status -> { }, () -> this.companion.controller().stop());

        Path prototypeMarker = FMLPaths.CONFIGDIR.get().resolve("covelliteai-prototype-test.flag");
        this.prototype = Files.exists(prototypeMarker) ? new PrototypeScenario(this.companion) : null;
        if (this.prototype != null) {
            LOGGER.info("Prototype verification scenario enabled by {}", prototypeMarker);
        }

        registerHandlers();
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "CovelliteAI-shutdown"));

        ensureBridge();
        LOGGER.info("CovelliteAI server-side companion configured: playerName={}, bridgePort={}",
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
        this.actions.pump(System.currentTimeMillis());
        this.bridgeApi.tick(current);
        this.companion.tick(current);
        if (this.prototype != null) {
            this.prototype.tick(current);
        }
    }

    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (this.prototype != null
                && this.server != null
                && event.getEntity() instanceof ServerPlayer player) {
            this.prototype.onPlayerLogin(player, this.server);
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        this.companion.despawn(event.getServer());
    }

    private void onServerStopped(ServerStoppedEvent event) {
        // Defensive cleanup in case stopping was skipped by a crash path.
        this.companion.despawn(event.getServer());
        this.server = null;
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        CompanionCommands.register(event.getDispatcher(), this.companion);
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
            LOGGER.info("CovelliteAI bridge listening on 127.0.0.1:{}", config.port);
        } catch (IOException e) {
            LOGGER.warn("Could not start bridge on 127.0.0.1:{} (will retry): {}", config.port, e.getMessage());
        }
    }

    private void shutdown() {
        BridgeServer b = bridge;
        if (b != null) b.close();
    }

    private static String loadOrCreateToken() throws IOException {
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
            Map<String, Object> response = dispatcher.dispatch(request);
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
        map.put("version", "0.1.0-SNAPSHOT");
        map.put("architecture", "in_server_companion");
        map.put("side", FMLEnvironment.dist.name());
        map.put("server_running", this.server != null && this.server.isRunning());
        map.put("bridge_authenticated", this.bridge != null && this.bridge.isAuthenticated());
        map.put("bridge_port", this.config.port);
        map.put("emergency_stop_active", this.actions.isEmergencyStopped());
        map.put("live_actions", this.actions.liveStatuses().size());
        map.put("companion", this.companion.state());
        map.put("recent_events", this.events.recent(10).size());
        return map;
    }
}
