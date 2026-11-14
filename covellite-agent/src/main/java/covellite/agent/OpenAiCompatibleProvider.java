package covellite.agent;

import covellite.core.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI chat-completions protocol.
 *
 * <p>One implementation covers every server that speaks
 * {@code /v1/chat/completions}: OpenAI itself, Ollama ({@code /v1}), LM Studio,
 * llama.cpp's server and any compatible gateway. Only the base URL and the
 * optional bearer token change.</p>
 */
public final class OpenAiCompatibleProvider implements AgentProvider {

    private final String id;
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final int timeoutMs;

    public OpenAiCompatibleProvider(String id, String baseUrl, String model,
                                    String apiKey, int timeoutMs) {
        this.id = id;
        this.baseUrl = HttpJson.trimSlash(baseUrl);
        this.model = model == null ? "" : model.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.timeoutMs = Math.max(2000, timeoutMs);
    }

    @Override
    public String id() {
        return this.id;
    }

    @Override
    public boolean ready() {
        return !this.baseUrl.isBlank() && (!this.model.isBlank() || this.id.equals("openai-compatible"));
    }

    @Override
    public Response complete(Request request) throws ProviderException {
        if (this.baseUrl.isBlank()) {
            throw new ProviderException("no base URL configured for provider " + this.id);
        }
        String modelName = this.model.isBlank() ? request.model() : this.model;
        if (modelName == null || modelName.isBlank()) {
            throw new ProviderException("no model configured for provider " + this.id);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxTokens());
        body.put("stream", false);

        List<Object> messages = new ArrayList<>();
        if (request.system() != null && !request.system().isBlank()) {
            messages.add(wireMessage(Message.system(request.system())));
        }
        for (Message message : request.messages()) {
            messages.add(wireMessage(message));
        }
        body.put("messages", messages);
        if (!request.tools().isEmpty()) {
            body.put("tools", wireTools(request.tools()));
            body.put("tool_choice", "auto");
        }

        Map<String, String> headers = new LinkedHashMap<>();
        if (!this.apiKey.isBlank()) {
            headers.put("Authorization", "Bearer " + this.apiKey);
        }

        Map<String, Object> json = HttpJson.post(this.baseUrl + "/chat/completions",
                headers, body, this.timeoutMs);

        List<Object> choices = HttpJson.list(json.get("choices"));
        if (choices.isEmpty()) {
            throw new ProviderException("no choices in response: " + Json.stringify(json));
        }
        Map<String, Object> choice = HttpJson.object(choices.get(0));
        Map<String, Object> message = HttpJson.object(choice.get("message"));
        String text = HttpJson.string(message.get("content"));
        String stop = HttpJson.string(choice.get("finish_reason"));

        List<ToolCall> calls = new ArrayList<>();
        for (Object rawCall : HttpJson.list(message.get("tool_calls"))) {
            Map<String, Object> call = HttpJson.object(rawCall);
            Map<String, Object> function = HttpJson.object(call.get("function"));
            String name = HttpJson.string(function.get("name"));
            if (name.isBlank()) {
                continue;
            }
            calls.add(new ToolCall(HttpJson.string(call.get("id")), name,
                    parseArguments(function.get("arguments"))));
        }
        return new Response(text, calls, stop);
    }

    private Map<String, Object> wireMessage(Message message) {
        Map<String, Object> wire = new LinkedHashMap<>();
        switch (message.role()) {
            case "tool" -> {
                wire.put("role", "tool");
                wire.put("tool_call_id", message.toolCallId() == null ? "" : message.toolCallId());
                wire.put("content", message.text());
            }
            case "assistant" -> {
                wire.put("role", "assistant");
                if (!message.text().isBlank()) {
                    wire.put("content", message.text());
                }
                if (!message.calls().isEmpty()) {
                    List<Object> calls = new ArrayList<>();
                    for (ToolCall call : message.calls()) {
                        Map<String, Object> wireCall = new LinkedHashMap<>();
                        wireCall.put("id", call.id());
                        wireCall.put("type", "function");
                        Map<String, Object> function = new LinkedHashMap<>();
                        function.put("name", call.name());
                        function.put("arguments", Json.stringify(call.arguments()));
                        wireCall.put("function", function);
                        calls.add(wireCall);
                    }
                    wire.put("tool_calls", calls);
                }
                if (message.text().isBlank() && message.calls().isEmpty()) {
                    wire.put("content", "");
                }
            }
            default -> {
                wire.put("role", message.role());
                wire.put("content", message.text());
            }
        }
        return wire;
    }

    private List<Object> wireTools(List<ToolSpec> tools) {
        List<Object> wire = new ArrayList<>();
        for (ToolSpec spec : tools) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", spec.name());
            function.put("description", spec.description());
            function.put("parameters", spec.inputSchema());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", "function");
            entry.put("function", function);
            wire.add(entry);
        }
        return wire;
    }

    private static Map<String, Object> parseArguments(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, value) -> out.put(String.valueOf(key), value));
            return out;
        }
        String text = raw instanceof String s ? s.trim() : "";
        if (text.isEmpty()) {
            return Map.of();
        }
        try {
            return Json.parseObject(text);
        } catch (RuntimeException e) {
            // Some local servers return arguments without JSON quoting; keep the
            // raw text instead of failing the whole turn.
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("raw", text);
            return fallback;
        }
    }
}
