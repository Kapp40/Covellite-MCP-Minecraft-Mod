package com.covelliteai;

import covellite.core.action.ActionQueue;
import covellite.core.dispatch.Dispatcher;
import covellite.core.event.EventLog;
import covellite.core.json.Json;
import covellite.core.net.BridgeServer;
import covellite.core.net.Handshake;
import covellite.core.state.StateDiff;
import covellite.core.state.StateSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NeoForge client entry point.
 *
 * <p>This mod is loaded into its own, independent Minecraft client instance.
 * It never touches the user's client. It exposes a localhost-only,
 * HMAC-authenticated TCP bridge and drives only {@code Minecraft.player} (the
 * local player of the instance it is installed in).</p>
 */
@Mod(value = CovelliteAI.MODID, dist = Dist.CLIENT)
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

    private volatile MinecraftGameAdapter adapter;
    private volatile BridgeServer bridge;
    private long nextBridgeAttempt;
    private long tickCounter;
    private StateSnapshot lastState;
    private boolean wasAuthenticated;
    private boolean autoConnectAttempted;

    public CovelliteAI() {
        instance = this;
        this.config = CovelliteConfig.load(FMLPaths.CONFIGDIR.get());
        this.events = new EventLog(config.maxChatHistory);
        this.actions = new ActionQueue(config.maxActions, config.maxActionHistory,
                status -> {}, () -> {
                    MinecraftGameAdapter a = adapter;
                    if (a != null) a.stopImmediate();
                });

        registerHandlers();
        NeoForge.EVENT_BUS.addListener(this::onClientTickPre);
        NeoForge.EVENT_BUS.addListener(this::onClientTickPost);
        NeoForge.EVENT_BUS.addListener(this::onMovementInput);
        NeoForge.EVENT_BUS.addListener(this::onChatReceived);
        NeoForge.EVENT_BUS.addListener(this::onLoggingOut);
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "CovelliteAI-shutdown"));

        ensureBridge();
        LOGGER.info("CovelliteAI client bridge configured: port={}, playerName={}",
                config.port, config.playerName);
    }

    public static CovelliteAI instance() {
        return instance;
    }

    // ------------------------------------------------------------------
    // Tick lifecycle
    // ------------------------------------------------------------------

    private void onClientTickPre(ClientTickEvent.Pre event) {
        ensureAdapter();
        processInbound();
        actions.pump(System.currentTimeMillis());
    }

    private void onClientTickPost(ClientTickEvent.Post event) {
        ensureAdapter();
        ensureBridge();
        tickAutoConnect();
        tickCounter++;

        boolean authenticated = bridge != null && bridge.isAuthenticated();
        if (!authenticated) {
            wasAuthenticated = false;
            return;
        }
        MinecraftGameAdapter a = adapter;
        if (a == null) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !a.isAiPlayer(player)) return;

        if (!wasAuthenticated) {
            wasAuthenticated = true;
            lastState = null;
            sendState(a.getPlayerState(), true);
        } else if (tickCounter % config.stateUpdateTicks == 0) {
            StateSnapshot current = new StateSnapshot(a.getPlayerState(), System.currentTimeMillis());
            if (lastState == null) {
                lastState = current;
                sendState(current.data(), true);
            } else {
                Map<String, Object> diff = StateDiff.diff(lastState, current);
                if (diff.containsKey("changed") || diff.containsKey("removed")) {
                    Map<String, Object> message = new LinkedHashMap<>();
                    message.put("type", "state_delta");
                    message.put("data", diff);
                    send(message);
                    lastState = current;
                }
            }
        }
    }

    private void tickAutoConnect() {
        if (!config.autoConnect || config.serverAddress.isBlank() || autoConnectAttempted) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) return;
        if (!(mc.screen instanceof TitleScreen)) return;
        autoConnectAttempted = true;
        try {
            ServerAddress address = ServerAddress.parseString(config.serverAddress);
            ServerData data = new ServerData(config.playerName, config.serverAddress,
                    ServerData.Type.OTHER);
            ConnectScreen.startConnecting(new TitleScreen(), mc, address, data, false, null);
            LOGGER.info("Auto-connecting CovelliteAI client to {}", config.serverAddress);
        } catch (Exception e) {
            LOGGER.warn("Auto-connect failed for {}: {}", config.serverAddress, e.getMessage());
        }
    }

    private void onMovementInput(MovementInputUpdateEvent event) {
        MinecraftGameAdapter a = adapter;
        if (a == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (event.getEntity() != mc.player) return;
        a.applyInput(event.getInput());
    }

    private void onChatReceived(ClientChatReceivedEvent event) {
        String sender = "system";
        String uuid = "";
        if (event.getBoundChatType() != null) {
            sender = event.getBoundChatType().name().getString();
        }
        if (!event.isSystem()) {
            uuid = event.getSender().toString();
            if (Minecraft.getInstance().getConnection() != null) {
                var info = Minecraft.getInstance().getConnection().getPlayerInfo(event.getSender());
                if (info != null) sender = info.getProfile().getName();
            }
        }
        String text = event.getMessage().getString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sender", sender);
        payload.put("uuid", uuid);
        payload.put("message", text);
        events.add("chat", payload);

        if (bridge != null && bridge.isAuthenticated()) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", "event");
            message.put("event", "chat");
            message.putAll(payload);
            send(message);
        }
    }

    private void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        actions.cancelAll();
        MinecraftGameAdapter a = adapter;
        if (a != null) a.stopImmediate();
        lastState = null;
        wasAuthenticated = false;
    }

    private void ensureAdapter() {
        if (adapter == null) {
            adapter = new MinecraftGameAdapter(actions, events, config.playerName);
        }
    }

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
    // Request dispatch (always on the client thread)
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

    private void sendState(Map<String, Object> data, boolean full) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", full ? "state" : "state_delta");
        message.put("data", data);
        send(message);
    }

    private void registerHandlers() {
        dispatcher.register("get_status", p -> status());
        dispatcher.register("get_player_state", p -> adapter().getPlayerState());
        dispatcher.register("get_world_info", p -> adapter().getWorldInfo());
        dispatcher.register("get_player_list", p -> adapter().getPlayerList());
        dispatcher.register("get_inventory", p -> adapter().getInventory());
        dispatcher.register("get_equipment", p -> adapter().getEquipment());
        dispatcher.register("get_hotbar", p -> adapter().getHotbar());
        dispatcher.register("get_nearby_entities", p -> adapter().getNearbyEntities(
                number(p, "radius", 16.0), integer(p, "limit", 32)));
        dispatcher.register("get_nearby_blocks", p -> adapter().getNearbyBlocks(
                number(p, "radius", 8.0), stringList(p, "blocks"), integer(p, "limit", 64)));
        dispatcher.register("get_targeted_object", p -> adapter().getTargetedObject());
        dispatcher.register("get_entity_info", p -> adapter().getEntityInfo(
                string(p, "entity", null)));
        dispatcher.register("get_block_info", p -> adapter().getBlockInfo(
                integer(p, "x", 0), integer(p, "y", 0), integer(p, "z", 0)));
        dispatcher.register("get_chat_history", p -> adapter().getChatHistory(
                integer(p, "limit", 20)));
        dispatcher.register("get_recent_events", p -> adapter().getRecentEvents(
                integer(p, "since", 0), integer(p, "limit", 32)));

        dispatcher.register("move", p -> actionResult(adapter().move(
                number(p, "forward", 0), number(p, "strafe", 0),
                bool(p, "sprint", false), integer(p, "ticks", 20))));
        dispatcher.register("look", p -> actionResult(adapter().look(
                number(p, "yaw", 0), number(p, "pitch", 0))));
        dispatcher.register("look_at", p -> actionResult(adapter().lookAt(
                number(p, "x", 0), number(p, "y", 0), number(p, "z", 0))));
        dispatcher.register("jump", p -> actionResult(adapter().jump()));
        dispatcher.register("set_sneak", p -> actionResult(adapter().setSneak(
                bool(p, "enabled", false))));
        dispatcher.register("stop", p -> {
            adapter().stop();
            return Map.of("stopped", true);
        });
        dispatcher.register("follow", p -> actionResult(adapter().follow(
                string(p, "player", null), number(p, "distance", 3.0))));
        dispatcher.register("goto", p -> actionResult(adapter().goTo(
                number(p, "x", 0), number(p, "y", 0), number(p, "z", 0),
                number(p, "tolerance", 1.0))));
        dispatcher.register("get_action_status", p -> adapter().getActionStatus(
                string(p, "id", null)));
        dispatcher.register("cancel_action", p -> {
            String id = string(p, "id", null);
            if (id == null) throw new IllegalArgumentException("missing id");
            return Map.of("cancelled", adapter().cancelAction(id));
        });
        dispatcher.register("emergency_stop", p -> {
            adapter().emergencyStop();
            return Map.of("stopped", true, "emergency_stop_active", true);
        });
        dispatcher.register("reset_emergency_stop", p -> {
            actions.resetEmergencyStop();
            return Map.of("emergency_stop_active", false);
        });
        dispatcher.register("send_chat", p -> {
            String message = string(p, "message", null);
            adapter().sendChat(message);
            return Map.of("sent", true);
        });
    }

    private Map<String, Object> status() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        MinecraftGameAdapter a = adapter;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mod", MODID);
        m.put("version", "0.1.0-SNAPSHOT");
        m.put("connected", player != null);
        m.put("ai_player", a != null && a.isAiPlayer(player));
        m.put("expected_player_name", config.playerName);
        m.put("bridge_authenticated", bridge != null && bridge.isAuthenticated());
        m.put("bridge_port", config.port);
        m.put("emergency_stop_active", actions.isEmergencyStopped());
        m.put("live_actions", actions.liveStatuses().size());
        if (player != null) {
            m.put("player_name", player.getGameProfile().getName());
            m.put("dimension", mc.level == null ? null : mc.level.dimension().location().toString());
            m.put("x", player.getX());
            m.put("y", player.getY());
            m.put("z", player.getZ());
        }
        return m;
    }

    private MinecraftGameAdapter adapter() {
        MinecraftGameAdapter a = adapter;
        if (a == null) throw new IllegalStateException("mod_not_initialized");
        return a;
    }

    private static Map<String, Object> actionResult(String id) {
        return Map.of("action_id", id);
    }

    // ------------------------------------------------------------------
    // Params
    // ------------------------------------------------------------------

    private static String string(Map<String, Object> params, String key, String fallback) {
        Object v = params.get(key);
        if (v == null) return fallback;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? fallback : s;
    }

    private static double number(Map<String, Object> params, String key, double fallback) {
        Object v = params.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    private static int integer(Map<String, Object> params, String key, int fallback) {
        Object v = params.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    private static boolean bool(Map<String, Object> params, String key, boolean fallback) {
        Object v = params.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) if (o != null) out.add(String.valueOf(o));
            return out;
        }
        return List.of();
    }
}
