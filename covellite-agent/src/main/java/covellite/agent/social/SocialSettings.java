package covellite.agent.social;

import java.util.Map;

/**
 * Tunables for Cove's own social initiative.
 *
 * <p>All of them come from {@code covemcp.properties} with safe defaults, so a
 * missing key never stops the mind from working and an intrusive one can be
 * quieted down without touching code.</p>
 */
public record SocialSettings(
        boolean enabled,
        int minGapMs,
        int thinkIntervalMs,
        int maxInitiations,
        int questionTtlMs,
        boolean initiativeActions) {

    public static SocialSettings defaults() {
        return new SocialSettings(true, 45_000, 40_000, 6, 10 * 60_000, true);
    }

    public static SocialSettings from(Map<String, String> values) {
        return new SocialSettings(
                bool(values, "agentSocialEnabled", true),
                clampInt(values, "agentSocialMinGapMs", 45_000, 5_000, 900_000),
                clampInt(values, "agentSocialThinkMs", 40_000, 5_000, 900_000),
                clampInt(values, "agentSocialMaxInitiations", 6, 1, 60),
                clampInt(values, "agentSocialQuestionTtlMs", 10 * 60_000, 60_000, 3_600_000),
                bool(values, "agentSocialInitiative", true));
    }

    public SocialSettings withEnabled(boolean value) {
        return new SocialSettings(value, this.minGapMs, this.thinkIntervalMs,
                this.maxInitiations, this.questionTtlMs, this.initiativeActions);
    }

    /** Gap between two spontaneous lines, shortened by a chatty personality. */
    public long gapFor(double chattiness) {
        double scaled = 1.3D - Math.max(0.0D, Math.min(1.0D, chattiness));
        return Math.max(5_000L, (long) (this.minGapMs * scaled));
    }

    private static boolean bool(Map<String, String> values, String key, boolean fallback) {
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    private static int clampInt(Map<String, String> values, String key, int fallback, int min, int max) {
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
