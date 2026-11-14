package covellite.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded conversation window.
 *
 * <p>Trimming removes whole exchanges, never a lone {@code tool} message: an
 * orphan tool result would make the next provider call invalid. Tool messages
 * keep their assistant call in front of them because the assistant entry is what
 * carries the call id.</p>
 */
public final class ConversationManager {

    private final List<AgentProvider.Message> messages = new ArrayList<>();
    private final int limit;

    public ConversationManager(int limit) {
        this.limit = Math.max(4, limit);
    }

    public synchronized void add(AgentProvider.Message message) {
        if (message == null) {
            return;
        }
        this.messages.add(message);
        trim();
    }

    public synchronized void addAll(List<AgentProvider.Message> batch) {
        this.messages.addAll(batch);
        trim();
    }

    public synchronized List<AgentProvider.Message> messages() {
        return List.copyOf(this.messages);
    }

    public synchronized int size() {
        return this.messages.size();
    }

    public synchronized void clear() {
        this.messages.clear();
    }

    /** Compact transcript used for logs and for the adventure diary. */
    public synchronized String transcript(int maxChars) {
        StringBuilder builder = new StringBuilder();
        for (AgentProvider.Message message : this.messages) {
            String role = message.role();
            if ("tool".equals(role)) {
                continue;
            }
            String line = ("user".equals(role) ? "jugador: " : "cove: ") + message.text();
            if (builder.length() + line.length() > maxChars) {
                break;
            }
            builder.append(line).append('\n');
        }
        return builder.toString();
    }

    private void trim() {
        while (this.messages.size() > this.limit) {
            this.messages.remove(0);
        }
        // Never start on a tool result: drop it together with nothing to answer.
        while (!this.messages.isEmpty() && "tool".equals(this.messages.get(0).role())) {
            this.messages.remove(0);
        }
    }
}
