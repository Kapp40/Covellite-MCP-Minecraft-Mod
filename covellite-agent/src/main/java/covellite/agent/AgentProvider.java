package covellite.agent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Provider-agnostic model interface.
 *
 * <p>One implementation per wire protocol: OpenAI-compatible (OpenAI, Ollama,
 * LM Studio, llama.cpp, any compatible gateway), Anthropic Messages and Google
 * Gemini. The runtime never talks HTTP itself, so adding a provider does not
 * touch the loop, the memory or the tool registry.</p>
 */
public interface AgentProvider extends AutoCloseable {

    /** One tool invocation requested by the model. */
    record ToolCall(String id, String name, Map<String, Object> arguments) {
        public ToolCall {
            if (id == null || id.isBlank()) {
                id = "call_" + UUID.randomUUID().toString().substring(0, 8);
            }
            if (name == null) {
                name = "";
            }
            if (arguments == null) {
                arguments = Map.of();
            }
        }
    }

    /** A tool exposed to the model, with its JSON schema. */
    record ToolSpec(String name, String description, Map<String, Object> inputSchema) {}

    /**
     * One conversation entry. {@code role} is {@code system}, {@code user},
     * {@code assistant} or {@code tool}. Assistant entries may carry
     * {@code calls}; tool entries always carry {@code toolCallId}.
     */
    record Message(String role, String text, List<ToolCall> calls,
                   String toolCallId, String toolName) {
        public Message {
            if (role == null) {
                role = "user";
            }
            if (text == null) {
                text = "";
            }
            if (calls == null) {
                calls = List.of();
            }
        }

        public static Message system(String text) {
            return new Message("system", text, List.of(), null, null);
        }

        public static Message user(String text) {
            return new Message("user", text, List.of(), null, null);
        }

        public static Message assistant(String text) {
            return new Message("assistant", text, List.of(), null, null);
        }

        public static Message assistant(String text, List<ToolCall> calls) {
            return new Message("assistant", text, calls, null, null);
        }

        public static Message tool(String callId, String name, String payload) {
            return new Message("tool", payload, List.of(), callId, name);
        }
    }

    /** A single completion request. Tools empty means "chat only". */
    record Request(String model, String system, List<Message> messages,
                   List<ToolSpec> tools, int maxTokens, double temperature) {}

    /** A single completion response. */
    record Response(String text, List<ToolCall> calls, String stopReason) {
        public Response {
            if (text == null) {
                text = "";
            }
            if (calls == null) {
                calls = List.of();
            }
            if (stopReason == null) {
                stopReason = "";
            }
        }

        public boolean wantsTools() {
            return !this.calls.isEmpty();
        }
    }

    /** Stable id such as {@code ollama} or {@code anthropic}. */
    String id();

    Response complete(Request request) throws ProviderException;

    /** False when the provider is missing credentials. */
    default boolean ready() {
        return true;
    }

    @Override
    default void close() {
    }
}
