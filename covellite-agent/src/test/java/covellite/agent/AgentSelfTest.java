package covellite.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import covellite.agent.brain.BrainArbiter;
import covellite.agent.social.Identity;
import covellite.agent.social.Interpretation;
import covellite.agent.social.MindDecision;
import covellite.agent.social.SocialInterpreter;
import covellite.agent.social.SocialPolicy;
import covellite.agent.social.SocialPrompt;
import covellite.agent.social.SocialSettings;
import covellite.agent.social.SocialSignals;
import covellite.agent.social.SocialState;
import covellite.agent.lang.CoveProvider;
import covellite.agent.lang.Lexicon;
import covellite.agent.lang.OfflineBrain;
import covellite.agent.lang.Parser;
import covellite.agent.lang.Realizer;
import covellite.agent.lang.Spanish;
import covellite.agent.lang.WorldFacts;
import covellite.agent.reflex.ReflexEngine;
import covellite.agent.reflex.ReflexInputs;
import covellite.agent.reflex.SurvivalReflexes;

/**
 * Self-test for the agent module. Runs without JUnit so it can be executed from
 * the Gradle {@code agentTest} task or from any shell during development.
 */
public final class AgentSelfTest {

    private static int passed;
    private static int failed;

    private AgentSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("covemcp-agent-test");

        memoryTest(dir.resolve("memory"));
        goalsTest(dir.resolve("goals"));
        personalityTest(dir.resolve("personality"));
        extensionTest(dir.resolve("extensions"));
        fishingTest();
        ledgerTest();
        reflexTest();
        brainTest();
        conversationTest();
        plannerParseTest();
        loopTest(dir.resolve("runtime"));
        socialTest(dir.resolve("social"));
        languageTest(dir.resolve("lang"));

        System.out.println("== agent self-test: " + passed + " passed, " + failed + " failed ==");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // Memory
    // ------------------------------------------------------------------

    private static void memoryTest(Path dir) {
        AgentMemory memory = new AgentMemory(dir, 100);
        memory.remember(AgentMemory.Kind.SEMANTIC, "la base esta en 100 64 -200", List.of("base"));
        memory.remember(AgentMemory.Kind.PREFERENCE, "al jugador le gusta construir con piedra", List.of("build"));
        memory.remember(AgentMemory.Kind.EPISODIC, "encontramos diamantes en una cueva", List.of("mining"));

        check("memory stores entries", memory.size() == 3);
        check("memory separates kinds", memory.all(AgentMemory.Kind.SEMANTIC).size() == 1);
        check("memory recalls by keyword",
                memory.search("base", 3).stream().anyMatch(e -> e.text().contains("base")));

        AgentMemory.Entry entry = memory.remember(AgentMemory.Kind.SEMANTIC, "prueba de borrado", List.of());
        check("memory forget removes", entry != null && memory.forget(entry.id()));

        AgentMemory reloaded = new AgentMemory(dir, 100);
        check("memory persists to disk", reloaded.all(AgentMemory.Kind.SEMANTIC).size() == 1);
    }

    // ------------------------------------------------------------------
    // Goals
    // ------------------------------------------------------------------

    private static void goalsTest(Path dir) {
        AgentGoals goals = new AgentGoals(dir);
        AgentGoals.Goal goal = goals.create("conseguir 10 hierro",
                List.of("encontrar veta", "minar", "volver a la base"));
        check("goal created active", goal.status() == AgentGoals.Status.ACTIVE);
        check("goal has steps", goal.steps().size() == 3);

        AgentGoals.Goal first = goals.advance(goal.id());
        check("advance marks one step", first != null && first.doneCount() == 1);
        check("advance reports progress", first.progressPercent() == 33);
        check("advance exposes next step", first.nextStep().equals("minar"));

        goals.advance(goal.id());
        AgentGoals.Goal done = goals.advance(goal.id());
        check("last step completes the goal", done != null && done.status() == AgentGoals.Status.DONE);
        check("completed goal leaves the active list", goals.active().isEmpty());

        AgentGoals reloaded = new AgentGoals(dir);
        check("goals persist to disk", reloaded.all().size() == 1);
        check("persisted goal keeps its progress",
                reloaded.all().get(0).progressPercent() == 100);

        AgentGoals.Goal second = goals.create("construir un refugio", List.of("juntar madera"));
        check("summary lists active goals", goals.summary().contains("refugio"));
        check("pause stops an active goal", goals.pause(second.id()) && goals.active().isEmpty());
        check("resume reactivates it", goals.resume(second.id()) && goals.active().size() == 1);
    }

    // ------------------------------------------------------------------
    // Brain arbitration
    // ------------------------------------------------------------------

    private static void brainTest() {
        BrainArbiter arbiter = new BrainArbiter(BrainArbiter.Brain.LOCAL);
        check("the selected brain may act", arbiter.mayAct(BrainArbiter.Brain.LOCAL));
        check("the other brain may not act", !arbiter.mayAct(BrainArbiter.Brain.MCP));

        arbiter.revoke("switch");
        check("revoked authority blocks everyone",
                !arbiter.mayAct(BrainArbiter.Brain.LOCAL) && !arbiter.mayAct(BrainArbiter.Brain.MCP));
        long revokedEpoch = arbiter.epoch();
        arbiter.activate(BrainArbiter.Brain.MCP, "command");
        check("the new brain takes over", arbiter.mayAct(BrainArbiter.Brain.MCP));
        check("the old brain stays out", !arbiter.mayAct(BrainArbiter.Brain.LOCAL));
        check("epoch moves on every transition", arbiter.epoch() > revokedEpoch);

        arbiter.pause("mcp_disconnected");
        check("pausing does not change the brain", arbiter.brain() == BrainArbiter.Brain.MCP);
        check("paused brain may not act", !arbiter.mayAct(BrainArbiter.Brain.MCP));
        check("pause reason is kept", arbiter.pauseReason().equals("mcp_disconnected"));
        arbiter.resume();
        check("resume grants the same brain again", arbiter.mayAct(BrainArbiter.Brain.MCP));

        check("switches are logged", arbiter.log().stream()
                .anyMatch(line -> line.contains("cerebro=mcp")));
        check("brain ids parse", BrainArbiter.Brain.parse("LOCAL") == BrainArbiter.Brain.LOCAL
                && BrainArbiter.Brain.parse("externo") == BrainArbiter.Brain.MCP
                && BrainArbiter.Brain.parse("otro") == null);
    }

    // ------------------------------------------------------------------
    // Layer-0 reflexes
    // ------------------------------------------------------------------

    private static void reflexTest() {
        check("calm inputs trigger nothing",
                new ReflexEngine(SurvivalReflexes.layer0()).evaluate(ReflexInputs.calm(), 0).isEmpty());
        check("ten survival reflexes", SurvivalReflexes.layer0().size() == 10);
        check("reflexes outrank every task", SurvivalReflexes.layer0().stream()
                .allMatch(reflex -> reflex.priority() >= 91));

        check("lava flees", fires(ReflexInputs.builder().lavaAdjacent(true), "lava_flee"));
        check("lava ahead flees", fires(ReflexInputs.builder().lavaAhead(true), "lava_flee"));
        check("lava under the target stops mining",
                fires(ReflexInputs.builder().lavaBelowTarget(true), "lava_below"));
        check("lava above the target stops mining",
                fires(ReflexInputs.builder().lavaAboveTarget(true), "lava_above"));
        check("ledge brakes", fires(ReflexInputs.builder().solidAhead(false).waterBelow(10), "ledge_brake"));
        check("no ledge reflex with solid ground ahead",
                !fires(ReflexInputs.builder().solidAhead(true).waterBelow(10), "ledge_brake"));
        check("long fall with water uses the bucket",
                fires(ReflexInputs.builder().fall(4.0D, true, 2), "mlg_water"));
        check("long fall without a bucket does nothing",
                !fires(ReflexInputs.builder().fall(4.0D, false, 2), "mlg_water"));
        check("low air swims up", fires(ReflexInputs.builder().air(40), "drown"));
        check("levitation holds still", fires(ReflexInputs.builder().levitating(true), "levitation"));
        check("freezing backs off", fires(ReflexInputs.builder().freezing(true), "freezing"));
        check("cobweb breaks", fires(ReflexInputs.builder().inCobweb(true), "cobweb"));
        check("primed tnt flees", fires(ReflexInputs.builder().primedTnt(6), "tnt_fuse"));
        check("distant tnt is ignored", !fires(ReflexInputs.builder().primedTnt(12), "tnt_fuse"));

        ReflexEngine engine = new ReflexEngine(SurvivalReflexes.layer0());
        ReflexEngine.Shot shot = engine.evaluate(
                ReflexInputs.builder().lavaAdjacent(true).primedTnt(2).build(), 100).orElseThrow();
        check("lava outranks tnt", shot.id().equals("lava_flee"));
        check("cooldown blocks an immediate repeat", engine.evaluate(
                ReflexInputs.builder().lavaAdjacent(true).build(), 105).isEmpty());
        check("history keeps the shot with its tick",
                engine.history().size() == 1 && engine.history().get(0).tick() == 100L);
        check("cooldown expires", engine.evaluate(
                ReflexInputs.builder().lavaAdjacent(true).build(), 130).isPresent());
    }

    private static boolean fires(ReflexInputs.Builder inputs, String expected) {
        ReflexEngine engine = new ReflexEngine(SurvivalReflexes.layer0());
        return engine.evaluate(inputs.build(), 10L)
                .map(shot -> shot.id().equals(expected))
                .orElse(false);
    }

    // ------------------------------------------------------------------
    // Fishing and trade payment
    // ------------------------------------------------------------------

    private static void fishingTest() {
        check("calm water keeps waiting", !FishingSignal.shouldReel(0.0D, 10, 400));
        check("a dip reels in", FishingSignal.shouldReel(-0.2D, 10, 400));
        check("dip threshold is exact",
                FishingSignal.dipped(FishingSignal.DIP_THRESHOLD - 0.001D)
                        && !FishingSignal.dipped(FishingSignal.DIP_THRESHOLD));
        check("the wait budget forces a reel", FishingSignal.shouldReel(0.0D, 400, 400));
        check("a lost bobber stops after the grace",
                FishingSignal.castLost(25) && !FishingSignal.castLost(5));
    }

    private static void ledgerTest() {
        List<ItemLedger.Slot> slots = List.of(
                new ItemLedger.Slot("minecraft:emerald", 3),
                new ItemLedger.Slot("minecraft:emerald", 5),
                new ItemLedger.Slot("minecraft:bread", 2));

        ItemLedger.Plan split = ItemLedger.plan(slots,
                ItemLedger.costs(new ItemLedger.Slot("minecraft:emerald", 6)));
        check("affordable cost is accepted", split.affordable());
        check("cost spreads across partial stacks",
                split.takeBySlot().get(0) == 3 && split.takeBySlot().get(1) == 3);
        check("plan takes exactly what it needs", split.taken() == 6);

        ItemLedger.Plan short_ = ItemLedger.plan(slots,
                ItemLedger.costs(new ItemLedger.Slot("minecraft:emerald", 9)));
        check("short payment is refused", !short_.affordable() && short_.takeBySlot().isEmpty());
        check("refusal names the missing item", short_.missing().equals("minecraft:emerald"));

        ItemLedger.Plan two = ItemLedger.plan(slots, ItemLedger.costs(
                new ItemLedger.Slot("minecraft:emerald", 2),
                new ItemLedger.Slot("minecraft:bread", 1)));
        check("two-cost trades are planned together",
                two.affordable() && two.taken() == 3);

        check("empty cost is a no-op",
                ItemLedger.plan(slots, ItemLedger.costs()).affordable()
                        && ItemLedger.plan(slots, ItemLedger.costs()).taken() == 0);
    }

    // ------------------------------------------------------------------
    // Extensions
    // ------------------------------------------------------------------

    private static void extensionTest(Path dir) {
        AgentMemory memory = new AgentMemory(dir.resolve("memory"), 100);
        AgentGoals goals = new AgentGoals(dir.resolve("goals"));
        AgentToolRegistry registry = new AgentToolRegistry();
        List<String> calls = new ArrayList<>();
        AgentToolRegistry.ToolExecutor remote = (name, args) -> {
            calls.add(name + "@" + args.get("x") + "," + args.get("y") + "," + args.get("z"));
            return Map.of("ok", true);
        };
        AgentExtensionContext context = new AgentExtensionContext(registry, remote, memory, goals, null);

        AgentExtension good = new AgentExtension() {
            @Override
            public String id() {
                return "good";
            }

            @Override
            public void register(AgentExtensionContext ctx) {
                ctx.tool(new AgentProvider.ToolSpec("ping", "probe", Map.of("type", "object")),
                        (name, args) -> Map.of("pong", true));
            }
        };
        AgentExtension broken = new AgentExtension() {
            @Override
            public String id() {
                return "broken";
            }

            @Override
            public void register(AgentExtensionContext ctx) {
                throw new IllegalStateException("boom");
            }
        };

        AgentExtensions extensions = new AgentExtensions().add(good).add(broken)
                .add(new ConstructionExtension());
        extensions.applyAll(context);

        check("extension tools registered", registry.has("ping"));
        check("extension tool executes",
                Boolean.TRUE.equals(invoke(registry, "ping").get("pong")));
        check("failing extension is isolated", extensions.failures().size() == 1
                && extensions.failures().get(0).startsWith("broken"));
        check("construction tools registered", registry.has("build_wall") && registry.has("build_box"));
        check("tool counts are tracked", extensions.toolCounts().getOrDefault("good", -1) == 1);

        calls.clear();
        Map<String, Object> wall = new LinkedHashMap<>();
        wall.put("x", 0);
        wall.put("y", 64);
        wall.put("z", 0);
        wall.put("length", 3);
        wall.put("height", 2);
        wall.put("axis", "x");
        wall.put("block", "minecraft:stone");
        Map<String, Object> wallResult = invoke(registry, "build_wall", wall);
        check("build_wall places length*height blocks", wallResult.get("placed").equals(6));
        check("build_wall walks the requested axis", calls.contains("place_block@2,65,0"));

        calls.clear();
        Map<String, Object> tunnel = new LinkedHashMap<>();
        tunnel.put("x", 5);
        tunnel.put("y", 40);
        tunnel.put("z", 7);
        tunnel.put("length", 2);
        tunnel.put("axis", "z");
        Map<String, Object> tunnelResult = invoke(registry, "mine_tunnel", tunnel);
        check("mine_tunnel breaks two layers", tunnelResult.get("broken").equals(4));
        check("mine_tunnel follows the axis", calls.contains("break_block@5,41,8"));

        Map<String, Object> clamped = new LinkedHashMap<>(wall);
        clamped.put("length", 500);
        Map<String, Object> clampedResult = invoke(registry, "build_wall", clamped);
        Integer placed = (Integer) clampedResult.get("placed");
        check("block budget caps runaway builds", placed != null && placed <= 256);
    }

    private static Map<String, Object> invoke(AgentToolRegistry registry, String tool) {
        return invoke(registry, tool, Map.of());
    }

    private static Map<String, Object> invoke(AgentToolRegistry registry, String tool,
                                              Map<String, Object> args) {
        try {
            return registry.invoke(tool, args);
        } catch (Exception e) {
            return Map.of("error", String.valueOf(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // Personality and learning
    // ------------------------------------------------------------------

    private static void personalityTest(Path dir) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("agentEnabled", "true");
        values.put("agentProvider", "lmstudio");
        values.put("agentModel", "fake-model");
        AgentSettings settings = AgentSettings.from(values, dir);
        AgentMemory memory = new AgentMemory(dir.resolve("memory"), 100);
        AgentToolRegistry registry = new AgentToolRegistry();

        AgentProvider offline = new AgentProvider() {
            @Override
            public String id() {
                return "offline";
            }

            @Override
            public boolean ready() {
                return false;
            }

            @Override
            public Response complete(Request request) throws ProviderException {
                throw new ProviderException("offline");
            }
        };

        AgentRuntime runtime = new AgentRuntime(settings, offline, registry, memory,
                new AgentGoals(dir.resolve("goals")));
        runtime.start();
        runtime.onHumanChat("T45_", "me gusta el oro y las espadas");

        List<AgentMemory.Entry> preferences = memory.search("oro", 5);
        check("preference learned from chat", preferences.stream()
                .anyMatch(entry -> entry.kind() == AgentMemory.Kind.PREFERENCE));
        check("preference tagged with the player", preferences.stream()
                .anyMatch(entry -> entry.tags().contains("T45_")));
        check("offline chat does not start a turn",
                "no_provider".equals(runtime.status().get("state")));

        check("personality switch accepted", runtime.setPersonality("curioso"));
        check("personality reflected in status",
                "curioso".equals(runtime.status().get("personality")));
        check("unknown personality rejected", !runtime.setPersonality("inventada"));
        check("language switch accepted", runtime.setLanguage("en"));
        check("language reflected in status", "en".equals(runtime.status().get("language")));
        runtime.close();
    }

    // ------------------------------------------------------------------
    // Conversation
    // ------------------------------------------------------------------

    private static void conversationTest() {
        ConversationManager conversation = new ConversationManager(4);
        conversation.add(AgentProvider.Message.user("hola"));
        conversation.add(AgentProvider.Message.assistant("que tal"));
        conversation.add(AgentProvider.Message.assistant("", List.of(
                new AgentProvider.ToolCall("c1", "move", Map.of()))));
        conversation.add(AgentProvider.Message.tool("c1", "move", "{}"));
        conversation.add(AgentProvider.Message.user("anda a la base"));
        conversation.add(AgentProvider.Message.user("dale"));

        check("conversation is bounded", conversation.size() <= 4);
        check("conversation never starts on a tool result",
                !"tool".equals(conversation.messages().get(0).role()));
    }

    // ------------------------------------------------------------------
    // Planner parsing
    // ------------------------------------------------------------------

    private static void plannerParseTest() {
        List<String> json = AgentPlanner.parseSteps("{\"steps\":[\"ir a 0 64 0\",\"minar hierro\"]}");
        check("planner parses json steps", json.size() == 2 && json.get(1).contains("hierro"));

        List<String> list = AgentPlanner.parseSteps("1. caminar\n2. romper bloque\n3. volver");
        check("planner parses numbered list", list.size() == 3);
    }

    // ------------------------------------------------------------------
    // Full loop with a scripted provider
    // ------------------------------------------------------------------

    private static void loopTest(Path dir) throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("agentEnabled", "true");
        values.put("agentProvider", "lmstudio");
        values.put("agentModel", "fake-model");
        values.put("agentMaxSteps", "4");
        values.put("agentPersonality", "companion");
        AgentSettings settings = AgentSettings.from(values, dir);
        check("settings parse", settings.enabled() && settings.provider().equals("lmstudio"));

        AgentMemory memory = new AgentMemory(settings.dataDir().resolve("memory"), 100);
        AgentProvider fake = new ScriptedProvider();
        AgentToolRegistry registry = new AgentToolRegistry();
        registry.register(new AgentProvider.ToolSpec("get_player_state", "estado",
                        Map.of("type", "object", "properties", Map.of())),
                (name, arguments) -> Map.of("hp", 20.0, "food", 18.0));

        AgentRuntime runtime = new AgentRuntime(settings, fake, registry, memory,
                new AgentGoals(settings.dataDir().resolve("goals")));
        List<String> spoken = new ArrayList<>();
        runtime.setChatSink(spoken::add);
        runtime.start();

        String answer = runtime.ask("como estas?", "test").get(20, TimeUnit.SECONDS);
        check("loop returns the final answer", answer.contains("listo"));
        check("loop spoke through the chat sink", spoken.size() == 1);
        check("loop executed the tool", ((ScriptedProvider) fake).toolExecuted);

        Map<String, Object> status = runtime.status();
        check("status reports idle", "idle".equals(status.get("state")));
        check("status counts one turn", ((Number) status.get("turns")).intValue() == 1);

        runtime.close();
    }

    /** Provider that asks for one tool call and then answers. */
    private static final class ScriptedProvider implements AgentProvider {
        private int calls;
        private boolean toolExecuted;

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public Response complete(Request request) {
            this.calls++;
            if (this.calls == 1) {
                return new Response("miro mi estado", List.of(
                        new ToolCall("call_1", "get_player_state", Map.of())), "tool_calls");
            }
            for (Message message : request.messages()) {
                if ("tool".equals(message.role()) && message.text().contains("hp")) {
                    this.toolExecuted = true;
                }
            }
            return new Response("listo, tengo 20 de vida", List.of(), "stop");
        }
    }

    // ------------------------------------------------------------------
    // Social layer: pending question, interpretation, policy, identity
    // ------------------------------------------------------------------

    private static void socialTest(Path dir) {
        // Large enough to clear the policy's 5 s floor between spontaneous lines.
        long t0 = 60_000L;
        SocialState state = new SocialState(dir.resolve("state.json"));
        state.ask("¿Donde queda la base?", "base", "perdi la referencia", "explorando montañas", t0);
        for (int i = 0; i < 3; i++) {
            state.recordHuman("mensaje " + i, SocialState.Intent.CHARLA, t0 + 1_000L + i);
        }
        SocialState reloaded = new SocialState(dir.resolve("state.json"));
        check("social: pending question survives a restart",
                reloaded.pending() != null && reloaded.pending().text().contains("base"));
        check("social: pending question keeps its conversation id",
                reloaded.pending() != null && reloaded.pending().conversationId() == 1L);
        check("social: pending question is present in the prompt",
                reloaded.promptBlock(t0 + 5_000L).contains("Pregunta pendiente"));

        reloaded.answered(t0 + 6_000L);
        check("social: an answer clears the question", reloaded.pending() == null);

        for (int i = 0; i < 40; i++) {
            reloaded.recordHuman("ruido " + i, SocialState.Intent.CHARLA, t0 + 7_000L + i);
        }
        check("social: exchange window stays bounded", reloaded.exchanges().size() <= 24);

        Interpretation joke = Interpretation.parse(
                "{\"intencion\":\"BROMA\",\"responde_pendiente\":true,\"dirigido_a_cove\":true,"
                        + "\"tema\":\"base\",\"entendido\":\"bromea con estar perdidos\"}", "x");
        check("interpretation: a joke is read as a joke", joke.joke());
        check("interpretation: a joke can answer the pending question", joke.answersPending());
        check("interpretation: a correction is an order",
                Interpretation.parse("{\"intencion\":\"CORRECCION\"}", "x").correction());
        check("interpretation: garbage never becomes an order",
                Interpretation.parse("no entendi nada", "fallback").intent()
                        == SocialState.Intent.DESCONOCIDA);
        check("interpretation: unknown never answers for the player",
                !Interpretation.parse("{}", "x").answersPending());
        check("interpretation: json is found inside prose",
                Interpretation.parse("claro: {\"intencion\":\"RESPUESTA\",\"responde_pendiente\":true} listo", "x")
                        .intent() == SocialState.Intent.RESPUESTA);

        // The interpreter asks the model: the interpretation is not a keyword table.
        Identity identity = new Identity(dir.resolve("identity.json"));
        SocialState asked = new SocialState(dir.resolve("asked.json"));
        asked.ask("¿Que hacemos ahora?", "plan", "estamos sin rumbo", "atardecer", t0);
        InterpreterStub stub = new InterpreterStub();
        SocialInterpreter interpreter = new SocialInterpreter(AgentSettings.off(dir), stub);
        Interpretation reading = interpreter.interpret("AIPlayer65", "nos perdimos, boludo", asked, identity);
        check("interpreter: the model decides the reading",
                reading.intent() == SocialState.Intent.BROMA && reading.answersPending());
        check("interpreter: the pending question reaches the model",
                stub.lastSystem.contains("¿Que hacemos ahora?"));

        SocialSettings cfg = new SocialSettings(true, 1_000, 1_000, 2, 60_000, true);
        SocialPolicy policy = new SocialPolicy(cfg);
        SocialState fresh = new SocialState(dir.resolve("fresh.json"));
        SocialSignals calm = new SocialSignals(t0, 1, 6.0D, 20.0F, 20, false, false, false, 0.0D,
                "plains", "disponible", 120_000L, 0L);
        check("policy: silence with nobody online",
                !policy.evaluate(SocialSignals.quiet(t0), fresh, identity, 0.6D, 0L).think());
        check("policy: silence while a task owns the body",
                !policy.evaluate(new SocialSignals(t0, 1, 6.0D, 20.0F, 20, true, false, false, 0.0D,
                        "", "", 0L, 0L), fresh, identity, 0.6D, 0L).think());
        check("policy: a prolonged silence is a reason to think",
                policy.evaluate(calm, fresh, identity, 0.6D, 0L).think());

        fresh.ask("¿Vamos al norte?", "rumbo", "estamos lejos", "llanura", t0);
        check("policy: it waits for the answer instead of talking over it",
                !policy.evaluate(calm.withNow(t0 + 30_000L), fresh, identity, 0.6D, 0L).think());
        check("policy: an unanswered question wakes the mind once",
                policy.evaluate(calm.withNow(t0 + 61_000L), fresh, identity, 0.6D, 0L).think());
        fresh.reminder();
        check("policy: it does not nag twice in a row",
                !policy.evaluate(calm.withNow(t0 + 62_000L), fresh, identity, 0.6D, 0L).think());
        fresh.expire(t0 + 63_000L);

        SocialSignals hungry = new SocialSignals(t0, 1, 6.0D, 20.0F, 4, false, false, false, 0.0D,
                "", "disponible", 0L, 0L);
        check("policy: hunger is a concrete reason",
                policy.evaluate(hungry, fresh, identity, 0.6D, 0L).think());
        check("policy: respects the cooldown between thoughts",
                !policy.evaluate(hungry.withNow(t0 + 2_000L), fresh, identity, 0.6D, t0 + 2_000L).think());

        SocialState capped = new SocialState(dir.resolve("capped.json"));
        capped.recordCove("che", SocialState.Intent.CHARLA, true, t0);
        capped.recordCove("mira", SocialState.Intent.CHARLA, true, t0);
        check("policy: initiative cap per window",
                !policy.evaluate(hungry.withNow(t0 + 5_000L), capped, identity, 0.6D, 0L).think());

        double before = identity.trait("cautela");
        identity.observe(Identity.Outcome.FALLO, "me empujo un creeper", t0);
        check("identity: failure makes it more cautious", identity.trait("cautela") > before);
        identity.observe(Identity.Outcome.DESCUBRIMIENTO, "una cueva con amatistas", t0);
        check("identity: discovery raises curiosity and mood",
                identity.mood(t0 + 1_000L).equals("curioso"));
        identity.opinion("Prefiero construir en las montañas");
        identity.opinion("Prefiero construir en las montañas");
        check("identity: opinions are deduplicated",
                identity.toMap().get("opinions") instanceof List<?> list && list.size() == 1);
        Identity reloadedIdentity = new Identity(dir.resolve("identity.json"));
        check("identity: traits survive a restart",
                Math.abs(reloadedIdentity.trait("cautela") - identity.trait("cautela")) < 0.0001D);

        check("mind: garbage is silence",
                MindDecision.parse("no se").action() == MindDecision.Action.SILENCIO);
        MindDecision talk = MindDecision.parse("{\"accion\":\"hablar\",\"mensaje\":\"che, mira esa cueva\","
                + "\"motivo\":\"curiosidad\",\"pregunta\":true}");
        check("mind: a spoken line can carry a question",
                talk.action() == MindDecision.Action.HABLAR && talk.asksQuestion());
        check("mind: an initiative needs an activity",
                MindDecision.parse("{\"accion\":\"iniciativa\",\"actividad\":\"conseguir comida\"}")
                        .action() == MindDecision.Action.INICIATIVA);
        check("mind: an empty message downgrades to silence",
                MindDecision.parse("{\"accion\":\"hablar\",\"mensaje\":\"\"}")
                        .action() == MindDecision.Action.SILENCIO);

        check("prompt: social block includes identity and pending question",
                SocialPrompt.block(asked, identity, t0).contains("Identidad")
                        && SocialPrompt.block(asked, identity, t0).contains("Que hacemos ahora"));
    }

    /** Returns a fixed interpretation while recording the prompt it received. */
    private static final class InterpreterStub implements AgentProvider {
        private String lastSystem = "";

        @Override
        public String id() {
            return "stub";
        }

        @Override
        public Response complete(Request request) {
            this.lastSystem = request.system();
            return new Response("{\"intencion\":\"BROMA\",\"responde_pendiente\":true,"
                    + "\"dirigido_a_cove\":true,\"tema\":\"perdidos\","
                    + "\"entendido\":\"se rie de estar perdidos\"}", List.of(), "stop");
        }
    }

    // ------------------------------------------------------------------
    // Embedded language engine: lexicon, morphology, parser, realizer, brain
    // ------------------------------------------------------------------

    private static void languageTest(Path dir) {
        Lexicon lexicon = Lexicon.shared();
        check("lexicon: loads the text knowledge base", lexicon.size() >= 80);
        check("lexicon: resolves a plural synonym",
                lexicon.lookup("troncos") != null
                        && "madera".equals(lexicon.lookup("troncos").canonical()));
        check("lexicon: resolves a synonym",
                "cueva".equals(lexicon.lookup("caverna").canonical()));

        check("morphology: voseo imperative of cortar",
                "corta".equals(Spanish.conjugate("cortar", Spanish.Tense.IMPERATIVO,
                        Spanish.Person.TU)));
        check("morphology: irregular imperative of ir",
                "anda".equals(Spanish.conjugate("ir", Spanish.Tense.IMPERATIVO,
                        Spanish.Person.TU)));
        check("morphology: voseo present of tener",
                "tenes".equals(Spanish.conjugate("tener", Spanish.Tense.PRESENTE,
                        Spanish.Person.TU)));
        check("morphology: plural of arbol", "arboles".equals(Spanish.plural("arbol")));
        check("morphology: strips accents for matching",
                "donde esta la base".equals(Spanish.normalize("¿Dónde está la base?")));

        Parser parser = new Parser();
        WorldFacts world = new WorldFacts("Lorex", "plains", false, 20.0F, 20, 10.0D, 1,
                "disponible", List.of(), List.of());

        Parser.Reading chop = parser.parse("cortá ese árbol", world, null);
        check("parser: imperative is an order", chop.act() == Parser.Act.ORDEN);
        check("parser: order resolves the existing tool", "chop_tree".equals(chop.action()));

        Parser.Reading goHome = parser.parse("andá a la base", world, null);
        check("parser: named place maps to return_home",
                goHome.act() == Parser.Act.ORDEN && "return_home".equals(goHome.action())
                        && goHome.places().contains("base"));

        Parser.Reading follow = parser.parse("seguime", world, null);
        check("parser: reflexive imperative is understood",
                follow.act() == Parser.Act.ORDEN && "follow".equals(follow.action()));

        Parser.Reading mine = parser.parse("miná hierro", world, null);
        check("parser: verb wins over the homonym place",
                mine.act() == Parser.Act.ORDEN && "mine".equals(mine.action())
                        && mine.items().contains("hierro"));

        Parser.Reading where = parser.parse("¿dónde estamos?", world, null);
        check("parser: a question is for Cove",
                where.act() == Parser.Act.PREGUNTA_A_COVE && where.addressed());

        SocialState.PendingQuestion pending = new SocialState.PendingQuestion(
                "¿Dónde queda la base?", "ubicacion", "me perdi", "montaña", 0L, 1L, 0);
        Parser.Reading joke = parser.parse("la base está en Marte, boludo", world, pending);
        check("parser: a joke that contradicts the world is not taken literally",
                joke.act() == Parser.Act.BROMA && joke.incongruent());
        check("parser: the joke still answers the pending question", joke.answersPending());

        Parser.Reading yes = parser.parse("sí, dale", world, pending);
        check("parser: a plain yes answers the question",
                yes.act() == Parser.Act.RESPUESTA_SI && yes.answersPending());

        Parser.Reading correction = parser.parse("no, te dije que fuéramos al otro lado", world, pending);
        check("parser: a correction is recognised", correction.act() == Parser.Act.CORRECCION);

        Parser.Reading laughing = parser.parse("jajaja nos perdimos", world, pending);
        check("parser: laughter answers without being an order",
                laughing.act() == Parser.Act.BROMA && laughing.answersPending());

        Realizer realizer = new Realizer();
        check("realizer: greets by name", realizer.greet("Lorex").contains("Lorex"));
        check("realizer: hunger question mentions food",
                realizer.ask("hambre", world, "Lorex").toLowerCase().contains("com"));
        String first = realizer.comment(world, null);
        String second = realizer.comment(world, null);
        check("realizer: does not repeat the same line", !first.equals(second));

        OfflineBrain brain = new OfflineBrain();
        List<String> tools = List.of("chop_tree", "mine", "follow", "return_home", "eat");
        List<AgentProvider.ToolCall> calls = brain.toolsFor("cortá el árbol", world, "Lorex", tools);
        check("brain: maps an order to an existing tool",
                calls.size() == 1 && "chop_tree".equals(calls.get(0).name()));
        List<AgentProvider.ToolCall> iron = brain.toolsFor("miná hierro", world, "Lorex", tools);
        check("brain: passes the material to mine",
                iron.size() == 1 && "iron".equals(iron.get(0).arguments().get("what")));
        List<AgentProvider.ToolCall> followCalls = brain.toolsFor("seguime", world, "Lorex", tools);
        check("brain: passes the player to follow",
                followCalls.size() == 1 && "Lorex".equals(followCalls.get(0).arguments().get("player")));

        WorldFacts hungryWorld = new WorldFacts("Lorex", "plains", false, 20.0F, 4, 10.0D, 1,
                "disponible", List.of(), List.of());
        Identity identity = new Identity(null);
        MindDecision hungry = brain.decide(hungryWorld, null, identity, null);
        check("brain: hunger becomes an initiative",
                hungry.action() == MindDecision.Action.INICIATIVA
                        && hungry.activity().contains("comer"));
        check("brain: a calm situation stays silent",
                brain.decide(world, null, identity, null).action() == MindDecision.Action.SILENCIO);
        WorldFacts nightWorld = new WorldFacts("Lorex", "plains", true, 20.0F, 20, 10.0D, 1,
                "disponible", List.of(), List.of());
        check("brain: night raises a question",
                brain.decide(nightWorld, null, identity, null).asksQuestion());

        Parser.Reading statement = parser.parse("me gusta comer pan", world, null);
        check("parser: an infinitive inside a statement is not an order", !statement.order());
        check("parser: a preference is recognised",
                statement.act() == Parser.Act.PREFERENCIA);

        Parser.Reading tired = parser.parse("estoy cansado", world, null);
        check("parser: a feeling is recognised",
                tired.act() == Parser.Act.SENTIMIENTO && "cansado".equals(tired.feeling()));

        Parser.Reading aboutCove = parser.parse("¿cómo estás?", world, null);
        check("parser: a question about Cove is not a world question",
                aboutCove.act() == Parser.Act.PREGUNTA_SOBRE_COVE);

        Parser.Reading praise = parser.parse("sos un genio", world, null);
        check("parser: praise is recognised", praise.act() == Parser.Act.ELOGIO);

        Parser.Reading negatedOrder = parser.parse("no cortes el árbol", world, null);
        check("parser: a negative order is recognised and negated",
                negatedOrder.order() && negatedOrder.negated());

        List<AgentProvider.ToolCall> initiative = brain.toolsFor("comer algo del inventario",
                world, "Lorex", tools);
        check("brain: an initiative verb phrase resolves its tool",
                initiative.size() == 1 && "eat".equals(initiative.get(0).name()));

        WorldFacts promptFacts = WorldFacts.fromPrompt(
                "ejemplo {} antes del mundo\n## Estado del mundo\n"
                        + "{\"companion\":{\"health\":7},\"biome\":\"desert\"}",
                WorldFacts.empty());
        Parser.Reading unsure = parser.parse("no sé", world, pending);
        check("parser: 'no se' answers without being a no",
                unsure.act() == Parser.Act.RESPUESTA_DATO && unsure.answersPending());

        check("world: reads the snapshot after its heading, not the first braces",
                Math.round(promptFacts.health()) == 7 && "desert".equals(promptFacts.biome()));

        check("settings: the embedded brain is the default and needs no key",
                AgentSettings.from(Map.of(), dir).provider().equals("cove")
                        && AgentSettings.from(Map.of(), dir).configured());

        CoveProvider provider = new CoveProvider();
        AgentProvider.Request interpreterPrompt = new AgentProvider.Request("cove",
                "Responde SOLO con un objeto JSON: \"intencion\"\n"
                        + "Preguntaste: \"¿Dónde queda la base?\"\nTema: ubicacion",
                List.of(AgentProvider.Message.user("Jugador Lorex dice: \"la base está en Marte, boludo\"")),
                List.of(), 300, 0.1D);
        Interpretation reading = Interpretation.parse(provider.complete(interpreterPrompt).text(), "x");
        check("provider: interprets a joke offline",
                reading.intent() == SocialState.Intent.BROMA && reading.answersPending());

        AgentProvider.Request mindPrompt = new AgentProvider.Request("cove",
                "Responde SOLO con un objeto JSON: {\"accion\":\"silencio|hablar|iniciativa\"}\n"
                        + "## Situacion\njugadores cerca: 1; vida 20; comida 3",
                List.of(AgentProvider.Message.user("Momento de decidir por tu cuenta. Devolve el JSON.")),
                List.of(), 300, 0.8D);
        check("provider: decides offline",
                MindDecision.parse(provider.complete(mindPrompt).text()).action()
                        == MindDecision.Action.INICIATIVA);

        AgentProvider.Request chatPrompt = new AgentProvider.Request("cove", "",
                List.of(AgentProvider.Message.user("[Jugador] Lorex: hola")), List.of(), 300, 0.4D);
        check("provider: answers a greeting offline",
                !provider.complete(chatPrompt).text().isBlank());

        AgentProvider.Request orderPrompt = new AgentProvider.Request("cove", "",
                List.of(AgentProvider.Message.user("[Jugador] Lorex: cortá ese árbol")),
                List.of(new AgentProvider.ToolSpec("chop_tree", "tala", Map.of())), 300, 0.4D);
        AgentProvider.Response orderResponse = provider.complete(orderPrompt);
        check("provider: turns an order into a tool call",
                orderResponse.calls().size() == 1
                        && "chop_tree".equals(orderResponse.calls().get(0).name()));

        AgentProvider.Request historyPrompt = new AgentProvider.Request("cove", "",
                List.of(
                        AgentProvider.Message.user("[Jugador] Lorex: cortá ese árbol"),
                        AgentProvider.Message.assistant("", List.of(
                                new AgentProvider.ToolCall("c1", "chop_tree", Map.of()))),
                        AgentProvider.Message.tool("c1", "chop_tree", "ok"),
                        AgentProvider.Message.assistant("listo"),
                        AgentProvider.Message.user("[Jugador] Lorex: hola")),
                List.of(new AgentProvider.ToolSpec("chop_tree", "tala", Map.of())), 300, 0.4D);
        check("provider: an old tool result does not hijack chat",
                !provider.complete(historyPrompt).text().isBlank()
                        && provider.complete(historyPrompt).text().contains("Lorex"));

        AgentProvider.Request pendingChat = new AgentProvider.Request("cove",
                "## Pregunta pendiente\nPreguntaste: \"¿Dónde queda la base?\"\nTema: ubicacion",
                List.of(AgentProvider.Message.user("[Jugador] Lorex: la base está en Marte")),
                List.of(), 300, 0.4D);
        check("provider: a joke in a normal turn is answered as a joke",
                provider.complete(pendingChat).text().toLowerCase().contains("jaja"));

        AgentProvider.Request negatedPrompt = new AgentProvider.Request("cove", "",
                List.of(AgentProvider.Message.user("[Jugador] Lorex: no cortes el árbol")),
                List.of(new AgentProvider.ToolSpec("chop_tree", "tala", Map.of())), 300, 0.4D);
        AgentProvider.Response negatedResponse = provider.complete(negatedPrompt);
        check("provider: a negative order is not executed", negatedResponse.calls().isEmpty());

        AgentProvider.Request failedTool = new AgentProvider.Request("cove", "",
                List.of(
                        AgentProvider.Message.user("[Jugador] Lorex: cortá ese árbol"),
                        AgentProvider.Message.assistant("", List.of(
                                new AgentProvider.ToolCall("c1", "chop_tree", Map.of()))),
                        AgentProvider.Message.tool("c1", "chop_tree", "{\"error\":\"sin arboles\"}")),
                List.of(), 300, 0.4D);
        String failureLine = provider.complete(failedTool).text();
        check("provider: reports a failed tool instead of faking success",
                !failureLine.isBlank() && !failureLine.toLowerCase().contains("ya voy"));
    }

    private static void check(String label, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  ok   " + label);
        } else {
            failed++;
            System.out.println("  FAIL " + label);
        }
    }
}
