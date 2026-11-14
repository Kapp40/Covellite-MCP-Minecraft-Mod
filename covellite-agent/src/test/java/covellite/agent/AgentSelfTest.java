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

        AgentRuntime runtime = new AgentRuntime(settings, fake, registry, memory);
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
