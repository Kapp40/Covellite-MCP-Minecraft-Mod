package com.covelliteai.server;

import com.covelliteai.CovelliteAI;
import com.covelliteai.CovelliteConfig;

import covellite.agent.AgentGoals;
import covellite.agent.AgentMemory;
import covellite.agent.AgentPlanner;
import covellite.agent.AgentProvider;
import covellite.agent.AgentRuntime;
import covellite.agent.AgentSettings;
import covellite.agent.AgentToolRegistry;
import covellite.agent.AgentTools;
import covellite.agent.ProviderException;
import covellite.agent.ProviderFactory;
import covellite.core.dispatch.Dispatcher;
import covellite.core.json.Json;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.server.MinecraftServer;

/**
 * Runs the in-process agent and executes its tools on the Minecraft server
 * thread.
 *
 * <p>Threading contract: model calls happen on the agent's own worker thread;
 * every world mutation is a {@code Dispatcher} request drained from
 * {@link #tick(MinecraftServer)} on the server thread, so the agent can never
 * touch level state off-thread. The MCP bridge keeps working exactly as before
 * and is completely independent of this class.</p>
 */
public final class AgentBridge implements AgentToolRegistry.ToolExecutor, AutoCloseable {

    private static final long TOOL_TIMEOUT_MS = 25_000L;
    private static final int DRAIN_BUDGET = 16;

    private record Pending(Map<String, Object> request,
                           CompletableFuture<Map<String, Object>> future) {
    }

    private final CovelliteConfig config;
    private final Path dataDir;
    private final ConcurrentLinkedQueue<Pending> outbound = new ConcurrentLinkedQueue<>();
    private final AtomicLong requestIds = new AtomicLong();

    private volatile Dispatcher dispatcher;
    private volatile MinecraftServer server;
    private volatile AgentRuntime runtime;
    private volatile String worldId = "";
    private volatile String state = "disabled";
    private volatile String lastError = "";

    public AgentBridge(CovelliteConfig config, Path dataDir) {
        this.config = config;
        this.dataDir = dataDir;
    }

    /** The dispatcher that owns every world tool. Must be called once at boot. */
    public void attach(Dispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    public boolean enabled() {
        return this.runtime != null;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Starts the runtime for a world. Safe to call repeatedly: an already
     * running runtime for the same world is kept.
     */
    public synchronized boolean start(MinecraftServer server, String worldName) {
        return start(server, worldName, false);
    }

    /**
     * @param force start even when {@code agentEnabled} is false in the config
     *              file, so {@code /cove ai on} works without editing anything
     */
    public synchronized boolean start(MinecraftServer server, String worldName, boolean force) {
        this.server = server;
        String sanitized = sanitize(worldName == null || worldName.isBlank() ? "world" : worldName);
        if (this.runtime != null && sanitized.equals(this.worldId)) {
            return true;
        }
        stopRuntime();

        AgentSettings settings = AgentSettings.from(
                this.config.properties, this.dataDir.resolve("agent").resolve(sanitized));
        this.worldId = sanitized;
        if (force && !settings.enabled()) {
            settings = settings.withEnabled(true);
        }
        if (!settings.enabled()) {
            this.state = "disabled";
            this.lastError = "agentEnabled=false";
            return false;
        }

        try {
            AgentProvider provider = ProviderFactory.create(settings);
            AgentMemory memory = new AgentMemory(settings.dataDir(), 800);
            AgentGoals goals = new AgentGoals(settings.dataDir());
            AgentPlanner planner = new AgentPlanner(settings, provider, memory);
            AgentToolRegistry tools = new AgentToolRegistry();
            AgentTools.registerAll(tools, this, memory, planner, goals);

            AgentRuntime created = new AgentRuntime(settings, provider, tools, memory, goals);
            created.setChatSink(this::speak);
            created.start();
            this.runtime = created;
            this.state = "idle";
            this.lastError = "";
            CovelliteAI.LOGGER.info("Cove agent ready: provider={} model={} world={} tools={}",
                    provider.id(), settings.model(), this.worldId, tools.names().size());
            return true;
        } catch (ProviderException | RuntimeException e) {
            this.runtime = null;
            this.state = "error";
            this.lastError = String.valueOf(e.getMessage());
            CovelliteAI.LOGGER.warn("Cove agent could not start: {}", e.getMessage());
            return false;
        }
    }

    public synchronized void stop() {
        stopRuntime();
        this.state = "stopped";
    }

    private void stopRuntime() {
        AgentRuntime current = this.runtime;
        this.runtime = null;
        if (current != null) {
            current.close();
        }
    }

    // ------------------------------------------------------------------
    // Server-thread plumbing
    // ------------------------------------------------------------------

    /** Drains tool calls queued by the agent thread. Called from the server tick. */
    public void tick(MinecraftServer current) {
        this.server = current;
        int budget = DRAIN_BUDGET;
        Pending pending;
        while (budget-- > 0 && (pending = this.outbound.poll()) != null) {
            Map<String, Object> response;
            try {
                response = requireDispatcher().dispatch(pending.request());
            } catch (RuntimeException e) {
                response = Map.of("error", Map.of("code", "internal_error",
                        "message", String.valueOf(e)));
            }
            pending.future().complete(response);
        }
    }

    private Dispatcher requireDispatcher() {
        Dispatcher current = this.dispatcher;
        if (current == null) {
            throw new IllegalStateException("world tools are not registered yet");
        }
        return current;
    }

    /**
     * Executes one tool for the agent. Blocks the calling (agent) thread only,
     * never the server thread.
     */
    @Override
    public Map<String, Object> execute(String name, Map<String, Object> arguments) {
        return call(name, arguments, TOOL_TIMEOUT_MS);
    }

    private Map<String, Object> call(String method, Map<String, Object> params, long timeoutMs) {
        Dispatcher current = requireDispatcher();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("id", "agent-" + this.requestIds.incrementAndGet());
        request.put("method", method);
        request.put("params", params == null ? Map.of() : params);

        MinecraftServer minecraftServer = this.server;
        if (minecraftServer != null && minecraftServer.isSameThread()) {
            // Already on the server thread (e.g. an extension calling back in):
            // dispatching inline is correct and avoids self-deadlock.
            return respond(current.dispatch(request));
        }

        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        this.outbound.add(new Pending(request, future));
        try {
            return respond(future.get(timeoutMs, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            throw new IllegalStateException(method + " timed out after " + timeoutMs + " ms "
                    + "(is the world paused or still loading?)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for " + method, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(String.valueOf(e.getCause()), e);
        }
    }

    private static Map<String, Object> respond(Map<String, Object> response) {
        if (response == null) {
            return Map.of();
        }
        if (response.get("error") instanceof Map<?, ?> error) {
            Object code = error.get("code");
            Object message = error.get("message");
            throw new IllegalStateException((code == null ? "error" : code) + ": "
                    + (message == null ? "unknown" : message));
        }
        if (response.get("result") instanceof Map<?, ?> result) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) result;
            return typed;
        }
        return Map.of();
    }

    /** Sends one line of companion chat without blocking the agent turn. */
    private void speak(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("id", "agent-chat-" + this.requestIds.incrementAndGet());
        request.put("method", "send_chat");
        request.put("params", Map.of("message", text));
        this.outbound.add(new Pending(request, new CompletableFuture<>()));
    }

    // ------------------------------------------------------------------
    // Game events
    // ------------------------------------------------------------------

    public void onHumanChat(String sender, String text) {
        AgentRuntime current = this.runtime;
        if (current != null) {
            current.onHumanChat(sender, text);
        }
    }

    public void onPlayerJoin(String name) {
        AgentRuntime current = this.runtime;
        if (current != null) {
            current.onPlayerJoin(name);
        }
    }

    public void updateSnapshot(Map<String, Object> snapshot) {
        AgentRuntime current = this.runtime;
        if (current != null) {
            current.setWorldSnapshot(Json.stringify(snapshot));
        }
    }

    // ------------------------------------------------------------------
    // Commands and status
    // ------------------------------------------------------------------

    public boolean ask(String text) {
        AgentRuntime current = this.runtime;
        if (current == null || !current.providerReady()) {
            return false;
        }
        current.ask(text, "command");
        return true;
    }

    public boolean plan(String goal) {
        AgentRuntime current = this.runtime;
        if (current == null || !current.providerReady()) {
            return false;
        }
        current.plan(goal);
        return true;
    }

    public boolean remember(String text) {
        AgentRuntime current = this.runtime;
        if (current == null) {
            return false;
        }
        current.remember(AgentMemory.Kind.SEMANTIC, text, List.of("command"));
        return true;
    }

    /** One line per active goal, for the in-game command. */
    public List<String> activeGoals() {
        AgentRuntime current = this.runtime;
        if (current == null) {
            return List.of();
        }
        return current.goals().active().stream()
                .map(goal -> goal.text() + " [" + goal.progressPercent() + "%] " + goal.nextStep())
                .toList();
    }

    public List<String> recentMemory(String kind, int limit) {
        AgentRuntime current = this.runtime;
        if (current == null) {
            return List.of();
        }
        AgentMemory.Kind parsed;
        try {
            parsed = AgentMemory.Kind.valueOf(kind.toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            parsed = AgentMemory.Kind.SEMANTIC;
        }
        return current.memory().recent(parsed, limit).stream().map(AgentMemory.Entry::text).toList();
    }

    public Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<>();
        AgentRuntime current = this.runtime;
        map.put("enabled", current != null);
        map.put("state", current == null ? this.state : "running");
        map.put("world", this.worldId);
        map.put("last_error", this.lastError);
        if (current != null) {
            map.put("runtime", current.status());
        }
        return map;
    }

    private static String sanitize(String value) {
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "-");
        return cleaned.isBlank() ? "world" : cleaned;
    }

    @Override
    public void close() {
        stop();
        this.outbound.clear();
    }
}
