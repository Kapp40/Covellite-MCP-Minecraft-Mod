package covellite.agent;

import covellite.core.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages protocol.
 *
 * <p>Wire differences handled here instead of leaking into the runtime:
 * content blocks instead of a plain string, {@code tool_use} /
 * {@code tool_result} blocks, and consecutive same-role messages merged because
 * the Messages API expects alternating turns.</p>
 */
public final class AnthropicProvider implements AgentProvider {

    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final int timeoutMs;

    public AnthropicProvider(String baseUrl, String model, String apiKey, int timeoutMs) {
        this.baseUrl = HttpJson.trimSlash(baseUrl);
        this.model = model == null ? "" : model.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.timeoutMs = Math.max(2000, timeoutMs);
    }

    @Override
    public String id() {
        return "anthropic";
    }

    @Override
    public boolean ready() {
        return !this.apiKey.isBlank() && !this.model.isBlank();
    }

    @Override
    public Response complete(Request request) throws ProviderException {
        if (this.apiKey.isBlank()) {
            throw new ProviderException("missing Anthropic API key (agentApiKeyFile)");
        }
        String modelName = this.model.isBlank() ? request.model() : this.model;
        if (modelName == null || modelName.isBlank()) {
            throw new ProviderException("no Anthropic model configured");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("max_tokens", request.maxTokens());
        body.put("temperature", request.temperature());
        if (request.system() != null && !request.system().isBlank()) {
            body.put("system", request.system());
        }
        body.put("messages", wireMessages(request.messages()));
        if (!request.tools().isEmpty()) {
            List<Object> tools = new ArrayList<>();
            for (ToolSpec spec : request.tools()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", spec.name());
                entry.put("description", spec.description());
                entry.put("input_schema", spec.inputSchema());
                tools.add(entry);
            }
            body.put("tools", tools);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", this.apiKey);
        headers.put("anthropic-version", "2023-06-01");

        String url = this.baseUrl.endsWith("/v1") ? this.baseUrl + "/messages" : this.baseUrl + "/v1/messages";
        Map<String, Object> json = HttpJson.post(url, headers, body, this.timeoutMs);

        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (Object rawBlock : HttpJson.list(json.get("content"))) {
            Map<String, Object> block = HttpJson.object(rawBlock);
            String type = HttpJson.string(block.get("type"));
            if (type.equals("text")) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(HttpJson.string(block.get("text")));
            } else if (type.equals("tool_use")) {
                String name = HttpJson.string(block.get("name"));
                if (!name.isBlank()) {
                    calls.add(new ToolCall(HttpJson.string(block.get("id")), name,
                            HttpJson.object(block.get("input"))));
                }
            }
        }
        return new Response(text.toString(), calls, HttpJson.string(json.get("stop_reason")));
    }

    private List<Object> wireMessages(List<Message> messages) {
        List<Object> wire = new ArrayList<>();
        String lastRole = "";
        List<Object> lastBlocks = null;

        for (Message message : messages) {
            String role;
            List<Object> blocks = new ArrayList<>();

            switch (message.role()) {
                case "assistant" -> {
                    role = "assistant";
                    if (!message.text().isBlank()) {
                        blocks.add(Map.of("type", "text", "text", message.text()));
                    }
                    for (ToolCall call : message.calls()) {
                        Map<String, Object> use = new LinkedHashMap<>();
                        use.put("type", "tool_use");
                        use.put("id", call.id());
                        use.put("name", call.name());
                        use.put("input", call.arguments());
                        blocks.add(use);
                    }
                    if (blocks.isEmpty()) {
                        blocks.add(Map.of("type", "text", "text", " "));
                    }
                }
                case "tool" -> {
                    role = "user";
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("type", "tool_result");
                    result.put("tool_use_id", message.toolCallId() == null ? "" : message.toolCallId());
                    result.put("content", message.text());
                    blocks.add(result);
                }
                default -> {
                    role = "user";
                    blocks.add(Map.of("type", "text", "text", message.text()));
                }
            }

            if (role.equals(lastRole) && lastBlocks != null) {
                lastBlocks.addAll(blocks);
            } else {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("role", role);
                entry.put("content", blocks);
                wire.add(entry);
                lastRole = role;
                lastBlocks = blocks;
            }
        }
        return wire;
    }

    @SuppressWarnings("unused")
    private static String dump(Object value) {
        return Json.stringify(value);
    }
}
