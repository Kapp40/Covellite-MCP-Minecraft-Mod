package covellite.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Ordered collection of extensions.
 *
 * <p>Two loading paths, both real: explicit registration from the mod (which is
 * how the built-in packs load) and {@link ServiceLoader} discovery for jars that
 * ship a {@code META-INF/services/covellite.agent.AgentExtension} file.</p>
 */
public final class AgentExtensions {

    private final List<AgentExtension> extensions = new ArrayList<>();
    private final Map<String, Integer> toolCounts = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();

    public AgentExtensions add(AgentExtension extension) {
        if (extension != null && extension.id() != null) {
            this.extensions.add(extension);
        }
        return this;
    }

    /**
     * Discovers extensions on the classpath. Missing or broken service entries
     * are ignored: discovery must never stop startup.
     */
    public AgentExtensions loadServices(ClassLoader loader) {
        try {
            ServiceLoader<AgentExtension> services = ServiceLoader.load(AgentExtension.class, loader);
            for (AgentExtension extension : services) {
                add(extension);
            }
        } catch (RuntimeException | ServiceConfigurationError e) {
            this.failures.add("serviceloader: " + e.getMessage());
        }
        return this;
    }

    /** Runs every extension, isolating failures per pack. */
    public void applyAll(AgentExtensionContext context) {
        for (AgentExtension extension : this.extensions) {
            int before = context.registry().names().size();
            try {
                extension.register(context);
                this.toolCounts.put(extension.id(), context.registry().names().size() - before);
            } catch (Exception | LinkageError e) {
                String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                this.failures.add(extension.id() + ": " + detail);
            }
        }
    }

    public List<String> ids() {
        List<String> ids = new ArrayList<>();
        for (AgentExtension extension : this.extensions) {
            ids.add(extension.id());
        }
        return ids;
    }

    public Map<String, Integer> toolCounts() {
        return Map.copyOf(this.toolCounts);
    }

    public List<String> failures() {
        return List.copyOf(this.failures);
    }

    public int size() {
        return this.extensions.size();
    }
}
