package covellite.agent;

import covellite.agent.social.Identity;
import covellite.agent.social.Interpretation;
import covellite.agent.social.Mind;
import covellite.agent.social.MindDecision;
import covellite.agent.social.SocialInterpreter;
import covellite.agent.social.SocialPolicy;
import covellite.agent.social.SocialPrompt;
import covellite.agent.social.SocialSettings;
import covellite.agent.social.SocialSignals;
import covellite.agent.social.SocialState;

import java.nio.file.Path;
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
import java.util.function.Supplier;

/**
 * Owns the AI runtime lifecycle.
 *
 * <p>All model calls and tool round-trips happen on a single daemon worker
 * thread. The Minecraft server thread only ever hands text in and reads status
 * out, so a slow or unreachable provider can never stall a tick. Turns are
 * serialized on purpose: one companion cannot act on two plans at once.</p>
 *
 * <p>Since the social layer exists, the runtime also owns Cove's own mind: the
 * conversation state (including the question it is waiting on), the evolving
 * identity and the periodic deliberation that lets it speak or act without
 * being addressed.</p>
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

    private final SocialState social;
    private final Identity identity;
    private final SocialInterpreter interpreter;
    private final Mind mind;
    private final AtomicBoolean socialQueued = new AtomicBoolean();
    private volatile SocialSettings socialSettings = SocialSettings.defaults();
    private volatile SocialPolicy policy = new SocialPolicy(SocialSettings.defaults());
    private volatile SocialSignals signals = SocialSignals.quiet(System.currentTimeMillis());
    private volatile Supplier<Boolean> authority = () -> true;
    private volatile long lastThinkAt;
    private volatile String lastSocialReason = "";
    private volatile String lastSocialMessage = "";
    private volatile int socialCycles;

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

        Path socialDir = settings.dataDir().resolve("social");
        this.social = new SocialState(socialDir.resolve("state.json"));
        this.identity = new Identity(socialDir.resolve("identity.json"));
        this.interpreter = new SocialInterpreter(settings, provider);
        this.mind = new Mind(settings, provider);

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

    /** The authority gate: the body's active brain must be LOCAL. */
    public void setAuthority(Supplier<Boolean> gate) {
        this.authority = gate == null ? () -> true : gate;
    }

    /** Applies the social tunables from the mod configuration. */
    public void configureSocial(SocialSettings socialSettings) {
        this.socialSettings = socialSettings == null ? SocialSettings.defaults() : socialSettings;
        this.policy = new SocialPolicy(this.socialSettings);
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
        try {
            this.worker.execute(() -> handleHumanChat(sender, clean));
        } catch (RejectedExecutionException ignored) {
            this.state = "closed";
        }
    }

    /**
     * Reads the message with the model, resolves (or keeps) the pending
     * question, and only then runs the normal turn with that reading in the
     * prompt. Runs on the agent worker.
     */
    private void handleHumanChat(String sender, String clean) {
        if (isClosed()) {
            return;
        }
        long now = System.currentTimeMillis();
        long started = now;
        this.busy.set(true);
        this.state = "listening";
        try {
            Interpretation reading = this.interpreter.interpret(sender, clean, this.social, this.identity);
            this.social.recordHuman(clean, reading.intent(), now);

            String pendingNote = "";
            SocialState.PendingQuestion pending = this.social.pending();
            if (pending != null) {
                long ageSeconds = Math.max(0L, now - pending.askedAt()) / 1000L;
                if (reading.answersPending()) {
                    this.social.answered(now);
                    pendingNote = "El jugador responde a tu pregunta pendiente: \""
                            + shorten(pending.text(), 140) + "\". Tema: "
                            + (pending.topic().isBlank() ? "sin tema" : pending.topic())
                            + ". Lectura: " + (reading.understood().isBlank()
                            ? "te contesto" : reading.understood())
                            + ". Continua desde ahi y no repitas la pregunta.";
                    this.identity.observe(reading.joke()
                            ? Identity.Outcome.BROMA : Identity.Outcome.CHARLA, clean, now);
                    this.memory.remember(AgentMemory.Kind.SEMANTIC,
                            "respuesta del jugador sobre \"" + shorten(pending.text(), 100) + "\": " + clean,
                            List.of("respuesta", sender));
                    if (!reading.newInfo().isBlank()) {
                        this.memory.remember(AgentMemory.Kind.SEMANTIC, reading.newInfo(),
                                List.of("dato", sender));
                    }
                } else {
                    pendingNote = "Seguis esperando respuesta a \"" + shorten(pending.text(), 140)
                            + "\" (hace " + ageSeconds + " s). Este mensaje no la responde; "
                            + "no la inventes ni la des por contestada.";
                }
            }
            if (reading.correction()) {
                this.identity.observe(Identity.Outcome.CORRECCION, clean, now);
                this.identity.opinion("El jugador me corrigio: " + shorten(clean, 120));
            }

            String turn = turnText(sender, clean, reading, pendingNote);
            this.state = "thinking";
            AgentLoop.Result result = runLoop(turn, "chat");
            this.turns.incrementAndGet();
            if (result.ok()) {
                this.lastAnswer = result.text();
                this.lastError = "";
                afterTurn(result.text(), "chat", now);
            } else {
                this.lastError = result.error() == null ? "unknown error" : result.error();
                this.state = "error";
            }
        } catch (RuntimeException | Error e) {
            this.lastError = String.valueOf(e);
            this.state = "error";
        } finally {
            this.lastTurnMs = System.currentTimeMillis() - started;
            this.busy.set(false);
            if (!"error".equals(this.state)) {
                this.state = "idle";
            }
        }
    }

    private static String turnText(String sender, String clean, Interpretation reading,
                                   String pendingNote) {
        StringBuilder builder = new StringBuilder();
        builder.append("[Jugador] ").append(sender).append(": ").append(clean).append('\n');
        builder.append("[Lo que entendiste] intencion=").append(reading.intent().name());
        if (!reading.topic().isBlank()) {
            builder.append("; tema=").append(reading.topic());
        }
        if (reading.joke()) {
            builder.append("; es una broma: no la tomes como dato literal");
        }
        if (reading.intent().isOrder()) {
            builder.append("; es una orden o correccion: actua");
        }
        if (reading.addressedToCove()) {
            builder.append("; te esta hablando a vos");
        }
        builder.append('\n');
        if (!reading.understood().isBlank()) {
            builder.append("[Lectura] ").append(reading.understood()).append('\n');
        }
        if (!pendingNote.isBlank()) {
            builder.append("[Conversacion] ").append(pendingNote).append('\n');
        }
        return builder.toString().trim();
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
        try {
            AgentLoop.Result result = runLoop(text, source);
            this.turns.incrementAndGet();
            if (result.ok()) {
                this.lastAnswer = result.text();
                this.lastError = "";
                this.state = "idle";
                afterTurn(result.text(), source, System.currentTimeMillis());
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
     * One model/tool loop. Goals and the social context (identity, pending
     * question, recent exchanges) travel with every turn, so the model never
     * loses the thread even when the conversation window drops it.
     */
    private AgentLoop.Result runLoop(String text, String source) {
        long now = System.currentTimeMillis();
        this.context.setExtraContext(this.goals.summary());
        this.context.setSocialContext(SocialPrompt.rules()
                + SocialPrompt.block(this.social, this.identity, now));
        this.conversation.add(AgentProvider.Message.user(text));
        return this.loop.run(this.conversation, this.chatSink, this.cancelled::get);
    }

    /** Records what Cove just said and, if it asked, what it is waiting on. */
    private void afterTurn(String answer, String source, long nowMs) {
        if (answer == null || answer.isBlank()) {
            return;
        }
        this.social.recordCove(answer, SocialState.Intent.CHARLA, false, nowMs);
        this.memory.remember(AgentMemory.Kind.CONVERSATION,
                "cove (" + source + "): " + shorten(answer, 200), List.of("cove", source));
        if (looksLikeQuestion(answer)) {
            this.social.ask(answer, "", source, snapshotBrief(), nowMs);
        }
    }

    // ------------------------------------------------------------------
    // Own initiative
    // ------------------------------------------------------------------

    /** Latest world reading used by the next deliberation. */
    public void updateSignals(SocialSignals latest) {
        if (latest != null) {
            this.signals = latest;
        }
    }

    /** Asks the runtime to consider speaking or acting on its own. Cheap to call often. */
    public void tickSocial() {
        if (isClosed() || !this.socialSettings.enabled()) {
            return;
        }
        if (!this.socialQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            this.worker.execute(() -> {
                try {
                    socialCycle();
                } finally {
                    this.socialQueued.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            this.socialQueued.set(false);
        }
    }

    private void socialCycle() {
        if (isClosed()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!providerReady()) {
            this.lastSocialReason = "sin_proveedor";
            return;
        }
        if (!this.authority.get()) {
            this.lastSocialReason = "cerebro_inactivo";
            return;
        }
        if (this.busy.get()) {
            this.lastSocialReason = "ocupado";
            return;
        }
        SocialSignals current = this.signals.withNow(now);
        SocialPolicy.Verdict verdict = this.policy.evaluate(current, this.social, this.identity,
                chattiness(), this.lastThinkAt);
        this.lastSocialReason = verdict.reason();
        if (!verdict.think()) {
            return;
        }
        this.lastThinkAt = now;
        this.socialCycles++;
        this.busy.set(true);
        this.state = "pensando";
        try {
            MindDecision decision = this.mind.think(this.settings,
                    AgentPersonality.of(this.settings.personality()), this.context.snapshot(),
                    this.social, this.identity, current, verdict.trigger(), chattiness());
            if (!decision.learning().isBlank()) {
                this.identity.opinion(decision.learning());
                this.memory.remember(AgentMemory.Kind.SEMANTIC,
                        "conclusion propia: " + decision.learning(), List.of("identidad"));
            }
            if (!decision.mood().isBlank()) {
                this.identity.setMood(decision.mood(), decision.motive(), now, 180_000L);
            }
            switch (decision.action()) {
                case SILENCIO -> this.lastSocialMessage = "";
                case HABLAR -> speakOwn(decision, now);
                case INICIATIVA -> {
                    if (decision.saysSomething()) {
                        speakOwn(decision, now);
                    }
                    if (this.socialSettings.initiativeActions() && !decision.activity().isBlank()) {
                        String prompt = "[Propia iniciativa] " + decision.activity()
                                + (decision.motive().isBlank() ? "" : " (motivo: " + decision.motive() + ")");
                        AgentLoop.Result result = runLoop(prompt, "iniciativa");
                        this.turns.incrementAndGet();
                        if (result.ok()) {
                            afterTurn(result.text(), "iniciativa", System.currentTimeMillis());
                        } else {
                            this.lastError = result.error();
                        }
                    }
                }
                default -> {
                }
            }
            this.state = "idle";
        } catch (RuntimeException e) {
            this.lastError = String.valueOf(e);
            this.state = "error";
        } finally {
            this.busy.set(false);
        }
    }

    private void speakOwn(MindDecision decision, long nowMs) {
        String line = decision.message() == null ? "" : decision.message().trim();
        if (line.isBlank()) {
            return;
        }
        this.lastSocialMessage = line;
        this.conversation.add(AgentProvider.Message.assistant(line));
        if (this.settings.replyInChat()) {
            this.chatSink.accept(line);
        }
        this.social.recordCove(line, SocialState.Intent.CHARLA, true, nowMs);
        this.memory.remember(AgentMemory.Kind.CONVERSATION,
                "cove (iniciativa): " + shorten(line, 200), List.of("cove", "iniciativa"));
        if (decision.asksQuestion() || looksLikeQuestion(line)) {
            this.social.ask(line, "", decision.motive().isBlank() ? "iniciativa" : decision.motive(),
                    snapshotBrief(), nowMs);
        }
    }

    private String snapshotBrief() {
        String snapshot = this.context.snapshot();
        return snapshot.length() > 600 ? snapshot.substring(0, 600) : snapshot;
    }

    private double chattiness() {
        double base = AgentPersonality.of(this.settings.personality()).chattiness();
        return Math.max(0.05D, Math.min(1.0D, (base * 0.7D) + (this.identity.trait("iniciativa") * 0.5D)));
    }

    private static boolean looksLikeQuestion(String text) {
        String trimmed = text.trim();
        return trimmed.endsWith("?") || trimmed.endsWith("¿") || trimmed.contains("¿");
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
        map.put("social", socialStatus());
        return map;
    }

    /** Everything the player needs to see why Cove spoke, asked or stayed quiet. */
    public Map<String, Object> socialStatus() {
        long now = System.currentTimeMillis();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", this.socialSettings.enabled());
        map.put("provider_ready", providerReady());
        map.put("authority", this.authority.get());
        map.put("last_reason", this.lastSocialReason);
        map.put("last_message", shorten(this.lastSocialMessage, 200));
        map.put("cycles", this.socialCycles);
        map.put("since_human_chat_ms", this.social.lastHumanChatAt() == 0L
                ? -1L : Math.max(0L, now - this.social.lastHumanChatAt()));
        map.put("since_cove_spoke_ms", this.social.lastSpokeAt() == 0L
                ? -1L : Math.max(0L, now - this.social.lastSpokeAt()));
        SocialState.PendingQuestion pending = this.social.pending();
        if (pending == null) {
            map.put("pending_question", null);
        } else {
            Map<String, Object> question = new LinkedHashMap<>();
            question.put("text", pending.text());
            question.put("topic", pending.topic());
            question.put("motive", pending.motive());
            question.put("age_ms", Math.max(0L, now - pending.askedAt()));
            question.put("conversation", pending.conversationId());
            question.put("reminders", pending.reminders());
            map.put("pending_question", question);
        }
        map.put("conversations", this.social.conversations());
        map.put("identity", this.identity.status(now));
        map.put("recent", this.social.exchanges().size());
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

    public SocialState social() {
        return this.social;
    }

    public Identity identity() {
        return this.identity;
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
