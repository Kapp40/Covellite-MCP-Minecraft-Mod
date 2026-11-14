package covellite.agent;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable configuration for the in-process AI runtime.
 *
 * <p>Every value has a safe default so a missing or partially written
 * {@code covemcp.properties} never prevents the mod from starting. The whole
 * runtime is disabled unless {@code agentEnabled} is true; the Java reflexes in
 * {@code BridgeApi} keep working either way.</p>
 */
public record AgentSettings(
        boolean enabled,
        String provider,
        String model,
        String baseUrl,
        Path apiKeyFile,
        int maxSteps,
        int timeoutMs,
        int maxTokens,
        double temperature,
        String personality,
        String language,
        boolean replyInChat,
        boolean wakeOnJoin,
        int historyLimit,
        Path dataDir) {

    public static final String DEFAULT_PROVIDER = "ollama";

    /**
     * Builds settings from the mod's property map. Unknown keys are ignored and
     * every malformed value falls back to its default instead of throwing.
     */
    public static AgentSettings from(Map<String, String> values, Path dataDir) {
        String provider = text(values, "agentProvider", DEFAULT_PROVIDER).toLowerCase(Locale.ROOT);
        String model = text(values, "agentModel", "");
        String baseUrl = text(values, "agentBaseUrl", "");
        String keyFile = text(values, "agentApiKeyFile", "");
        Path apiKeyFile = keyFile.isEmpty() ? null : Path.of(keyFile);
        return new AgentSettings(
                bool(values, "agentEnabled", false),
                provider,
                model,
                baseUrl,
                apiKeyFile,
                clampInt(text(values, "agentMaxSteps", "8"), 8, 1, 32),
                clampInt(text(values, "agentTimeoutMs", "45000"), 45000, 2000, 300000),
                clampInt(text(values, "agentMaxTokens", "1200"), 1200, 128, 8192),
                clampDouble(text(values, "agentTemperature", "0.4"), 0.4, 0.0, 2.0),
                text(values, "agentPersonality", "companion").toLowerCase(Locale.ROOT),
                text(values, "agentLanguage", "auto"),
                bool(values, "agentReplyInChat", true),
                bool(values, "agentWakeOnJoin", true),
                clampInt(text(values, "agentHistoryLimit", "24"), 24, 4, 128),
                dataDir);
    }

    /** Settings with the runtime switched off, used when nothing is configured. */
    public static AgentSettings off(Path dataDir) {
        return new AgentSettings(false, DEFAULT_PROVIDER, "", "", null, 8, 45000, 1200,
                0.4, "companion", "auto", true, true, 24, dataDir);
    }

    /** Copy of these settings with another personality profile. */
    public AgentSettings withPersonality(String value) {
        return new AgentSettings(this.enabled, this.provider, this.model, this.baseUrl,
                this.apiKeyFile, this.maxSteps, this.timeoutMs, this.maxTokens, this.temperature,
                value == null ? this.personality : value, this.language, this.replyInChat,
                this.wakeOnJoin, this.historyLimit, this.dataDir);
    }

    /** Copy of these settings with another response language. */
    public AgentSettings withLanguage(String value) {
        return new AgentSettings(this.enabled, this.provider, this.model, this.baseUrl,
                this.apiKeyFile, this.maxSteps, this.timeoutMs, this.maxTokens, this.temperature,
                this.personality, value == null ? this.language : value, this.replyInChat,
                this.wakeOnJoin, this.historyLimit, this.dataDir);
    }

    /** Copy of these settings with the runtime flag overridden. */
    public AgentSettings withEnabled(boolean value) {
        return new AgentSettings(value, this.provider, this.model, this.baseUrl, this.apiKeyFile,
                this.maxSteps, this.timeoutMs, this.maxTokens, this.temperature,
                this.personality, this.language, this.replyInChat, this.wakeOnJoin,
                this.historyLimit, this.dataDir);
    }

    /** True when the provider can plausibly answer a request. */
    public boolean configured() {
        if (!this.enabled) {
            return false;
        }
        // Local servers often have a default model, so only the cloud providers
        // require an explicit model name here.
        return !this.model.isBlank() || isLocal();
    }

    public boolean isLocal() {
        switch (this.provider) {
            case "ollama", "lmstudio", "llamacpp", "local" -> {
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    public String effectiveBaseUrl() {
        if (!this.baseUrl.isBlank()) {
            return this.baseUrl;
        }
        return switch (this.provider) {
            case "ollama" -> "http://127.0.0.1:11434/v1";
            case "lmstudio", "lm-studio" -> "http://127.0.0.1:1234/v1";
            case "llamacpp", "llama.cpp" -> "http://127.0.0.1:8080/v1";
            case "openai" -> "https://api.openai.com/v1";
            case "anthropic" -> "https://api.anthropic.com";
            case "gemini", "google" -> "https://generativelanguage.googleapis.com";
            default -> "http://127.0.0.1:11434/v1";
        };
    }

    private static String text(Map<String, String> values, String key, String fallback) {
        String raw = values.get(key);
        if (raw == null) {
            return fallback;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() && !fallback.isEmpty() ? fallback : trimmed;
    }

    private static boolean bool(Map<String, String> values, String key, boolean fallback) {
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    private static int clampInt(String raw, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double clampDouble(String raw, double fallback, double min, double max) {
        try {
            double value = Double.parseDouble(raw.trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
