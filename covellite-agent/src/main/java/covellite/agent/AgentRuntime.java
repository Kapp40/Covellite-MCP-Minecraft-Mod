package covellite.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Owns the AI runtime lifecycle.
 *
 * <p>All model calls and tool round-trips happen on a single daemon worker
 * thread. The Minecraft server thread only ever hands text in and reads status
 * out, so a slow or unreachable provider can never stall a tick. Turns are
 * serialized on purpose: one companion cannot act on two plans at once.</p>
 */
public final class AgentRuntime implements AutoCloseable {

    private volatile AgentSettings settings;
    private final AgentProvider provider;
    private final AgentMemory memory;
    private final AgentGoals goals;
    private final AgentToolRegistry tools;
    private final AgentContext context;
    private final ConversationManager conversation;
    private final AgentLoop loop;
    private final AgentPlanner planner;
    private final ExecutorService worker;

    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong turns = new AtomicLong();

    private volatile Consumer<String> chatSink = text -> {
    };
    private volatile String state = "starting";
    private volatile String lastAnswer = "";
    private volatile String lastError = "";
    private volatile long lastTurnMs;

    /**
     * Builds a runtime. The provider must already be constructed (see
     * {@link ProviderFactory}); this class never reads configuration files.
     */
    public AgentRuntime(AgentSettings settings, AgentProvider provider,
                        AgentToolRegistry tools, AgentMemory memory, AgentGoals goals) {
        this.settings = settings;
        this.provider = provider;
        this.memory = memory;
        this.goals = goals;
        this.tools = tools;
        this.context = new AgentContext(memory);
        this.conversation = new ConversationManager(settings.historyLimit());
        this.loop = new AgentLoop(settings, provider, tools, this.context, memory);
        this.planner = new AgentPlanner(settings, provider, memory);
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "CoveMCP-agent");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        this.state = "idle";
    }

    /** Where in-game chat lines go. Called from the agent thread. */
    public void setChatSink(Consumer<String> sink) {
        this.chatSink = sink == null ? text -> {
        } : sink;
    }

    public void setWorldSnapshot(String json) {
        this.context.setWorldSnapshot(json);
    }

    public void setExtraContext(String text) {
        this.context.setExtraContext(text);
    }

    public boolean providerReady() {
        return this.provider.ready();
    }

    /** Human chat line: always recorded, answered only when a provider is ready. */
    public void onHumanChat(String sender, String text) {
        if (isClosed() || text == null || text.isBlank()) {
            return;
        }
        String clean = text.trim();
        this.memory.remember(AgentMemory.Kind.CONVERSATION,
                "jugador " + sender + ": " + clean, List.of("player", sender));
        // Learning that does not need the model: explicit likes and dislikes are
        // remembered immediately, even while the provider is offline.
        learnPreferences(sender, clean, this.memory);
        if (!providerReady()) {
            this.state = "no_provider";
            return;
        }
        ask("[Minecraft] " + sender + ": " + clean, "chat");
    }

    /** Automatic wake-up when a player joins the world. */
    public void onPlayerJoin(String name) {
        if (isClosed() || !this.settings.wakeOnJoin() || !providerReady()) {
            return;
        }
        ask("[Sistema] " + name + " acaba de entrar al mundo. Saludalo en una linea y espera su pedido.",
                "join");
    }

    /** Queues one turn and returns the answer the model produced. */
    public CompletableFuture<String> ask(String text, String source) {
        CompletableFuture<String> future = new CompletableFuture<>();
        if (isClosed()) {
            future.completeExceptionally(new IllegalStateException("agent runtime is closed"));
            return future;
        }
        if (text == null || text.isBlank()) {
            future.complete("");
            return future;
        }
        try {
            this.worker.execute(() -> runTurn(text, source, future));
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    private void runTurn(String text, String source, CompletableFuture<String> future) {
        long started = System.currentTimeMillis();
        this.busy.set(true);
        this.cancelled.set(false);
        this.state = "thinking";
        // Goals travel with every turn: the model must see what is still pending
        // even after the conversation window dropped the original request.
        this.context.setExtraContext(this.goals.summary());
        try {
            this.conversation.add(AgentProvider.Message.user(text));
            AgentLoop.Result result = this.loop.run(this.conversation, this.chatSink,
                    this.cancelled::get);
            this.turns.incrementAndGet();
            if (result.ok()) {
                this.lastAnswer = result.text();
                this.lastError = "";
                this.state = "idle";
                if (!result.text().isBlank()) {
                    this.memory.remember(AgentMemory.Kind.EPISODIC,
                            "turno (" + source + "): " + shorten(result.text(), 220),
                            List.of("turn"));
                }
                future.complete(result.text());
            } else {
                this.lastError = result.error() == null ? "unknown error" : result.error();
                this.state = "error";
                future.completeExceptionally(new IllegalStateException(this.lastError));
            }
        } catch (RuntimeException | Error e) {
            this.lastError = String.valueOf(e);
            this.state = "error";
            future.completeExceptionally(e);
        } finally {
            this.lastTurnMs = System.currentTimeMillis() - started;
            this.busy.set(false);
        }
    }

    /**
     * Switches personality profile at runtime. The loop reads it on the next
     * turn, so no restart is needed.
     */
    public boolean setPersonality(String name) {
        if (!AgentPersonality.exists(name)) {
            return false;
        }
        this.settings = this.settings.withPersonality(name.trim());
        this.loop.setSettings(this.settings);
        return true;
    }

    /** Switches response language ({@code auto} mirrors the player). */
    public boolean setLanguage(String language) {
        if (language == null || language.isBlank()) {
            return false;
        }
        this.settings = this.settings.withLanguage(language.trim());
        this.loop.setSettings(this.settings);
        return true;
    }

    /**
     * Heuristic learning from plain chat: "me gusta X", "prefiero X", "odio X"
     * and similar become PREFERENCE memories tagged with the speaker.
     */
    static void learnPreferences(String sender, String text, AgentMemory memory) {
        if (text == null || text.isBlank()) {
            return;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        String[] markers = {"me gusta", "me encanta", "prefiero", "quiero siempre",
                "no me gusta", "no me gustan", "odio", "detesto", "nunca quiero",
                "siempre quiero", "mi favorito es", "mi favorita es"};
        for (String marker : markers) {
            if (!lower.contains(marker)) {
                continue;
            }
            String clean = text.trim();
            if (clean.length() > 240) {
                clean = clean.substring(0, 240);
            }
            memory.remember(AgentMemory.Kind.PREFERENCE, sender + ": " + clean,
                    List.of("player", sender));
            return;
        }
    }

    /** Requests cooperative cancellation of the running turn. */
    public void cancel() {
        this.cancelled.set(true);
    }

    /** Plans a goal without executing it. Runs on the agent thread. */
    public CompletableFuture<List<String>> plan(String goal) {
        CompletableFuture<List<String>> future = new CompletableFuture<>();
        if (isClosed()) {
            future.completeExceptionally(new IllegalStateException("agent runtime is closed"));
            return future;
        }
        try {
            this.worker.execute(() -> {
                try {
                    future.complete(this.planner.decompose(goal));
                } catch (ProviderException | RuntimeException e) {
                    this.lastError = String.valueOf(e);
                    future.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    public void remember(AgentMemory.Kind kind, String text, List<String> tags) {
        this.memory.remember(kind, text, tags);
    }

    public boolean busy() {
        return this.busy.get();
    }

    private boolean isClosed() {
        return this.closed.get();
    }

    public Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("state", this.state);
        map.put("provider", this.provider.id());
        map.put("provider_ready", providerReady());
        map.put("model", this.settings.model());
        map.put("base_url", this.settings.effectiveBaseUrl());
        map.put("personality", AgentPersonality.of(this.settings.personality()).name());
        map.put("language", this.settings.language());
        map.put("busy", this.busy.get());
        map.put("turns", this.turns.get());
        map.put("last_turn_ms", this.lastTurnMs);
        map.put("last_error", this.lastError);
        map.put("last_answer", shorten(this.lastAnswer, 200));
        map.put("conversation_size", this.conversation.size());
        map.put("tools", new ArrayList<Object>(this.tools.names()));
        map.put("memory", this.memory.counts());
        map.put("goals", this.goals.status());
        return map;
    }

    public AgentSettings settings() {
        return this.settings;
    }

    public AgentProvider provider() {
        return this.provider;
    }

    public AgentMemory memory() {
        return this.memory;
    }

    public AgentGoals goals() {
        return this.goals;
    }

    public AgentToolRegistry tools() {
        return this.tools;
    }

    public ConversationManager conversation() {
        return this.conversation;
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        this.cancelled.set(true);
        this.worker.shutdownNow();
        this.state = "closed";
        try {
            this.provider.close();
        } catch (RuntimeException ignored) {
            // A provider that fails to close must not break shutdown.
        }
    }
}
