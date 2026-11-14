package covellite.agent;

import covellite.core.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The autonomous reasoning/execution cycle.
 *
 * <p>One turn is: model call -> optional tool calls -> tool results -> model
 * call again, bounded by {@code maxSteps}, a per-call timeout inside the
 * provider and a hard wall-clock deadline for the whole turn. Any provider
 * failure ends the turn with a message instead of throwing into the game, and
 * three consecutive tool failures abort the loop so a broken tool cannot spin
 * forever.</p>
 */
public final class AgentLoop {

    /** Outcome of one turn. */
    public record Result(boolean ok, String text, int steps, String error, List<String> toolsUsed) {
        public static Result failure(String error, int steps, List<String> toolsUsed) {
            return new Result(false, "", steps, error, List.copyOf(toolsUsed));
        }
    }

    private static final int MAX_TOOL_PAYLOAD = 3000;
    private static final int MAX_CONSECUTIVE_TOOL_ERRORS = 3;

    private volatile AgentSettings settings;
    private final AgentProvider provider;
    private final AgentToolRegistry tools;
    private final AgentContext context;
    private final AgentMemory memory;

    public AgentLoop(AgentSettings settings, AgentProvider provider, AgentToolRegistry tools,
                     AgentContext context, AgentMemory memory) {
        this.settings = settings;
        this.provider = provider;
        this.tools = tools;
        this.context = context;
        this.memory = memory;
    }

    /** Applies personality/language changes without rebuilding the loop. */
    public void setSettings(AgentSettings settings) {
        this.settings = settings;
    }

    public Result run(ConversationManager conversation, Consumer<String> speak,
                      BooleanSupplier cancelled) {
        List<String> used = new ArrayList<>();
        int steps = 0;
        int consecutiveErrors = 0;
        long deadline = System.currentTimeMillis()
                + Math.max(5000L, this.settings.timeoutMs()) * Math.max(1, this.settings.maxSteps());
        String lastText = "";

        for (int step = 1; step <= this.settings.maxSteps(); step++) {
            steps = step;
            if (cancelled != null && cancelled.getAsBoolean()) {
                return Result.failure("cancelled", steps, used);
            }
            if (System.currentTimeMillis() > deadline) {
                return Result.failure("turn deadline exceeded", steps, used);
            }

            List<AgentProvider.Message> history = conversation.messages();
            String system = this.context.systemPrompt(this.settings, AgentPersonality.of(this.settings.personality()), history);
            AgentProvider.Request request = new AgentProvider.Request(
                    this.settings.model(), system, history, this.tools.specs(),
                    this.settings.maxTokens(), this.settings.temperature());

            AgentProvider.Response response;
            try {
                response = this.provider.complete(request);
            } catch (ProviderException e) {
                this.memory.remember(AgentMemory.Kind.ERROR,
                        "proveedor " + this.provider.id() + ": " + e.getMessage(), List.of("provider"));
                return Result.failure(e.getMessage(), steps, used);
            } catch (RuntimeException e) {
                this.memory.remember(AgentMemory.Kind.ERROR,
                        "error inesperado del proveedor: " + e, List.of("provider"));
                return Result.failure(String.valueOf(e), steps, used);
            }

            lastText = response.text();

            if (!response.wantsTools()) {
                if (!lastText.isBlank()) {
                    conversation.add(AgentProvider.Message.assistant(lastText));
                    if (this.settings.replyInChat() && speak != null) {
                        speak.accept(lastText);
                    }
                }
                return new Result(true, lastText, steps, "", List.copyOf(used));
            }

            conversation.add(AgentProvider.Message.assistant(lastText, response.calls()));

            for (AgentProvider.ToolCall call : response.calls()) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    return Result.failure("cancelled", steps, used);
                }
                used.add(call.name());
                Map<String, Object> output;
                try {
                    output = this.tools.invoke(call.name(), call.arguments());
                    consecutiveErrors = 0;
                } catch (Exception e) {
                    String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    output = new LinkedHashMap<>();
                    output.put("error", detail);
                    consecutiveErrors++;
                    this.memory.remember(AgentMemory.Kind.ERROR,
                            call.name() + " fallo: " + detail, List.of("tool", call.name()));
                }
                conversation.add(AgentProvider.Message.tool(call.id(), call.name(),
                        truncate(Json.stringify(output))));
                if (consecutiveErrors >= MAX_CONSECUTIVE_TOOL_ERRORS) {
                    return Result.failure("too many consecutive tool failures", steps, used);
                }
            }
        }

        return Result.failure("step limit reached after " + steps + " steps", steps, used);
    }

    private static String truncate(String payload) {
        if (payload == null) {
            return "";
        }
        return payload.length() <= MAX_TOOL_PAYLOAD ? payload : payload.substring(0, MAX_TOOL_PAYLOAD);
    }
}
