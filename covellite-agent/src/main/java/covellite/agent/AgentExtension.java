package covellite.agent;

/**
 * A self-contained feature pack for the companion.
 *
 * <p>Extensions exist because adding a capability should not mean editing the
 * 6.9k-line {@code BridgeApi} or the core tool catalog. Each extension gets the
 * shared context and registers whatever it needs: tools (world or local),
 * memory usage, goals. A failing extension is reported and skipped, so one bad
 * pack cannot take the companion offline.</p>
 *
 * <p>Implementation notes for a new extension:</p>
 * <ul>
 *   <li>Register every world tool through {@link AgentExtensionContext#tool}
 *       using the {@code remote} executor: it already marshals onto the
 *       Minecraft server thread.</li>
 *   <li>Never touch the world directly from an extension; compose existing
 *       tools instead.</li>
 *   <li>Keep {@link #id()} stable and lowercase: it is used in logs and status.</li>
 * </ul>
 */
public interface AgentExtension {

    /** Stable id such as {@code construction}. */
    String id();

    /** Short human description shown in status output. */
    default String description() {
        return id();
    }

    /**
     * Registers the extension. May throw: the registry records the failure and
     * keeps the already-registered tools of the other extensions.
     */
    void register(AgentExtensionContext context) throws Exception;
}
