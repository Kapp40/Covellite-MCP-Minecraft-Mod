package covellite.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registry of the tools the in-process agent can call.
 *
 * <p>This is the runtime-facing catalog: each entry pairs the JSON schema the
 * model sees with the executor that performs it. The executor is supplied by the
 * mod, which marshals the call onto the Minecraft server thread; the agent
 * itself never touches the world.</p>
 */
public final class AgentToolRegistry {

    /** Performs one tool call. May throw; the loop turns that into a tool error. */
    public interface ToolExecutor {
        Map<String, Object> execute(String name, Map<String, Object> arguments) throws Exception;
    }

    private final Map<String, AgentProvider.ToolSpec> specs = new LinkedHashMap<>();
    private final Map<String, ToolExecutor> executors = new LinkedHashMap<>();

    public synchronized void register(AgentProvider.ToolSpec spec, ToolExecutor executor) {
        if (spec == null || spec.name() == null || spec.name().isBlank()) {
            throw new IllegalArgumentException("tool needs a name");
        }
        this.specs.put(spec.name(), spec);
        this.executors.put(spec.name(), executor);
    }

    public synchronized boolean has(String name) {
        return this.executors.containsKey(name);
    }

    public synchronized List<AgentProvider.ToolSpec> specs() {
        return List.copyOf(this.specs.values());
    }

    public synchronized List<String> names() {
        return List.copyOf(this.executors.keySet());
    }

    public synchronized Map<String, Object> invoke(String name, Map<String, Object> arguments)
            throws Exception {
        ToolExecutor executor = this.executors.get(name);
        if (executor == null) {
            throw new IllegalArgumentException("unknown tool: " + name
                    + " (available: " + String.join(", ", this.executors.keySet()) + ")");
        }
        Map<String, Object> safeArgs = arguments == null ? Map.of() : arguments;
        Map<String, Object> result = executor.execute(name, safeArgs);
        return result == null ? Map.of() : result;
    }

    /** Case-insensitive name test used to keep local tools separate. */
    public synchronized boolean hasIgnoreCase(String name) {
        if (name == null) {
            return false;
        }
        String needle = name.toLowerCase(Locale.ROOT);
        for (String candidate : this.executors.keySet()) {
            if (candidate.toLowerCase(Locale.ROOT).equals(needle)) {
                return true;
            }
        }
        return false;
    }

    /** Copies every registration from another registry (used for extensions). */
    public synchronized void mergeFrom(AgentToolRegistry other) {
        List<String> names = new ArrayList<>(other.executors.keySet());
        for (String name : names) {
            this.specs.put(name, other.specs.get(name));
            this.executors.put(name, other.executors.get(name));
        }
    }
}
