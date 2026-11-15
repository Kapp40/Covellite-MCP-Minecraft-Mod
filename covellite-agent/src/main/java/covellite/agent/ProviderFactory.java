package covellite.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Builds the provider selected in the mod configuration.
 *
 * <p>The API key is never stored in the repository or in the world save: it is
 * read from the file named by {@code agentApiKeyFile} or from a process
 * environment variable, in that order.</p>
 */
public final class ProviderFactory {

    private ProviderFactory() {
    }

    public static AgentProvider create(AgentSettings settings) throws ProviderException {
        String key = resolveApiKey(settings);
        String provider = settings.provider() == null ? "" : settings.provider().toLowerCase(Locale.ROOT);
        String baseUrl = settings.effectiveBaseUrl();
        return switch (provider) {
            // The embedded brain: pure Java, no HTTP and no credentials.
            case "cove", "local", "embedded", "offline" ->
                    new covellite.agent.lang.CoveProvider();
            case "anthropic", "claude" ->
                    new AnthropicProvider(baseUrl, settings.model(), key, settings.timeoutMs());
            case "gemini", "google" ->
                    new GeminiProvider(baseUrl, settings.model(), key, settings.timeoutMs());
            default ->
                    new OpenAiCompatibleProvider(provider, baseUrl, settings.model(), key,
                            settings.timeoutMs());
        };
    }

    private static String resolveApiKey(AgentSettings settings) throws ProviderException {
        if (settings.apiKeyFile() != null) {
            try {
                String value = Files.readString(settings.apiKeyFile(), StandardCharsets.UTF_8).trim();
                if (!value.isEmpty()) {
                    return value;
                }
            } catch (IOException e) {
                throw new ProviderException("cannot read agentApiKeyFile "
                        + settings.apiKeyFile() + ": " + e.getMessage(), e);
            }
        }
        String[] candidates = switch (settings.provider() == null ? "" : settings.provider()) {
            case "anthropic", "claude" -> new String[]{"COVEMCP_API_KEY", "ANTHROPIC_API_KEY"};
            case "gemini", "google" -> new String[]{"COVEMCP_API_KEY", "GEMINI_API_KEY",
                    "GOOGLE_API_KEY"};
            case "openai" -> new String[]{"COVEMCP_API_KEY", "OPENAI_API_KEY"};
            default -> new String[]{"COVEMCP_API_KEY"};
        };
        for (String name : candidates) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
