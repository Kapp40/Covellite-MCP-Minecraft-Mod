package covellite.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Per-turn context: the latest world snapshot plus the memories that actually
 * match what the player just said.
 *
 * <p>The snapshot is written by the mod once every few seconds and read on the
 * agent thread, so building a prompt never touches the server thread.</p>
 */
public final class AgentContext {

    private final AgentMemory memory;
    private volatile String snapshot = "{}";
    private volatile String extra = "";

    public AgentContext(AgentMemory memory) {
        this.memory = memory;
    }

    public void setWorldSnapshot(String json) {
        if (json != null && !json.isBlank()) {
            this.snapshot = json.length() > 4000 ? json.substring(0, 4000) : json;
        }
    }

    /** Free-form context injected by extensions (e.g. active extension list). */
    public void setExtraContext(String text) {
        this.extra = text == null ? "" : text;
    }

    public String snapshot() {
        return this.snapshot;
    }

    /**
     * Builds the system prompt, retrieving memories related to the newest user
     * message instead of dumping the whole store into the context.
     */
    public String systemPrompt(AgentSettings settings, AgentPersonality personality,
                               List<AgentProvider.Message> history) {
        String query = lastUserText(history);
        StringBuilder recall = new StringBuilder();
        List<AgentMemory.Entry> entries = new ArrayList<>();
        entries.addAll(this.memory.search(query, 6));
        if (entries.isEmpty()) {
            entries.addAll(this.memory.recent(AgentMemory.Kind.PREFERENCE, 3));
        } else {
            entries.addAll(this.memory.recent(AgentMemory.Kind.PREFERENCE, 2));
        }
        for (AgentMemory.Entry entry : entries) {
            if (recall.length() > 1200) {
                break;
            }
            recall.append("- [").append(entry.kind().name().toLowerCase(Locale.ROOT)).append("] ")
                    .append(entry.text()).append('\n');
        }
        String snapshotWithExtra = this.extra.isBlank()
                ? this.snapshot
                : this.snapshot + "\n" + this.extra;
        return personality.systemPrompt(settings.language(), snapshotWithExtra, recall.toString());
    }

    private static String lastUserText(List<AgentProvider.Message> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            AgentProvider.Message message = history.get(i);
            if ("user".equals(message.role())) {
                return message.text();
            }
        }
        return "";
    }
}
