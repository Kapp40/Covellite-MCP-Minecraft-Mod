package covellite.agent;

/**
 * Handles an extension receives: the shared registry, the executor that runs
 * world tools on the server thread, and the persistent stores.
 */
public final class AgentExtensionContext {

    private final AgentToolRegistry registry;
    private final AgentToolRegistry.ToolExecutor remote;
    private final AgentMemory memory;
    private final AgentGoals goals;
    private final AgentPlanner planner;

    public AgentExtensionContext(AgentToolRegistry registry,
                                 AgentToolRegistry.ToolExecutor remote,
                                 AgentMemory memory,
                                 AgentGoals goals,
                                 AgentPlanner planner) {
        this.registry = registry;
        this.remote = remote;
        this.memory = memory;
        this.goals = goals;
        this.planner = planner;
    }

    public AgentToolRegistry registry() {
        return this.registry;
    }

    /** Executor for tools implemented by the mod (server-thread safe). */
    public AgentToolRegistry.ToolExecutor remote() {
        return this.remote;
    }

    public AgentMemory memory() {
        return this.memory;
    }

    public AgentGoals goals() {
        return this.goals;
    }

    public AgentPlanner planner() {
        return this.planner;
    }

    /** Convenience: registers one tool in the shared registry. */
    public void tool(AgentProvider.ToolSpec spec, AgentToolRegistry.ToolExecutor executor) {
        this.registry.register(spec, executor);
    }
}
