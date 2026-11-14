package covellite.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Google Gemini {@code generateContent} protocol.
 *
 * <p>Gemini uses {@code contents} with {@code parts} and
 * {@code functionCall}/{@code functionResponse} parts, so the tool loop is
 * translated here and stays invisible to the runtime.</p>
 */
public final class GeminiProvider implements AgentProvider {

    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final int timeoutMs;

    public GeminiProvider(String baseUrl, String model, String apiKey, int timeoutMs) {
        this.baseUrl = HttpJson.trimSlash(baseUrl);
        this.model = model == null ? "" : model.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.timeoutMs = Math.max(2000, timeoutMs);
    }

    @Override
    public String id() {
        return "gemini";
    }

    @Override
    public boolean ready() {
        return !this.apiKey.isBlank() && !this.model.isBlank();
    }

    @Override
    public Response complete(Request request) throws ProviderException {
        if (this.apiKey.isBlank()) {
            throw new ProviderException("missing Gemini API key (agentApiKeyFile)");
        }
        String modelName = this.model.isBlank() ? request.model() : this.model;
        if (modelName == null || modelName.isBlank()) {
            throw new ProviderException("no Gemini model configured");
        }
        if (modelName.startsWith("models/")) {
            modelName = modelName.substring("models/".length());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        if (request.system() != null && !request.system().isBlank()) {
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", request.system()))));
        }
        body.put("contents", wireContents(request.messages()));
        if (!request.tools().isEmpty()) {
            List<Object> declarations = new ArrayList<>();
            for (ToolSpec spec : request.tools()) {
                Map<String, Object> declaration = new LinkedHashMap<>();
                declaration.put("name", spec.name());
                declaration.put("description", spec.description());
                declaration.put("parameters", spec.inputSchema());
                declarations.add(declaration);
            }
            body.put("tools", List.of(Map.of("functionDeclarations", declarations)));
        }
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("temperature", request.temperature());
        generationConfig.put("maxOutputTokens", request.maxTokens());
        body.put("generationConfig", generationConfig);

        String url = this.baseUrl + "/v1beta/models/" + modelName + ":generateContent?key=" + this.apiKey;
        Map<String, Object> json = HttpJson.post(url, Map.of(), body, this.timeoutMs);

        List<Object> candidates = HttpJson.list(json.get("candidates"));
        if (candidates.isEmpty()) {
            throw new ProviderException("no candidates in Gemini response");
        }
        Map<String, Object> candidate = HttpJson.object(candidates.get(0));
        Map<String, Object> content = HttpJson.object(candidate.get("content"));

        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (Object rawPart : HttpJson.list(content.get("parts"))) {
            Map<String, Object> part = HttpJson.object(rawPart);
            String chunk = HttpJson.string(part.get("text"));
            if (!chunk.isEmpty()) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(chunk);
            }
            Map<String, Object> call = HttpJson.object(part.get("functionCall"));
            String name = HttpJson.string(call.get("name"));
            if (!name.isBlank()) {
                calls.add(new ToolCall(null, name, HttpJson.object(call.get("args"))));
            }
        }
        return new Response(text.toString(), calls, HttpJson.string(candidate.get("finishReason")));
    }

    private List<Object> wireContents(List<Message> messages) {
        List<Object> contents = new ArrayList<>();
        for (Message message : messages) {
            String role = message.role().equals("assistant") ? "model" : "user";
            List<Object> parts = new ArrayList<>();
            if (message.role().equals("tool")) {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("name", message.toolName() == null ? "tool" : message.toolName());
                response.put("response", Map.of("result", message.text()));
                parts.add(Map.of("functionResponse", response));
            } else {
                if (!message.text().isBlank()) {
                    parts.add(Map.of("text", message.text()));
                }
                for (ToolCall call : message.calls()) {
                    parts.add(Map.of("functionCall",
                            Map.of("name", call.name(), "args", call.arguments())));
                }
            }
            if (parts.isEmpty()) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", role);
            entry.put("parts", parts);
            contents.add(entry);
        }
        return contents;
    }
}
