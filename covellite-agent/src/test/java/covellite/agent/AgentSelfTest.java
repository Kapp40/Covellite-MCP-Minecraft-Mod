package covellite.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
        conversationTest();
        plannerParseTest();
        loopTest(dir.resolve("runtime"));

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
