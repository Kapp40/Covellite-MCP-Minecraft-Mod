package covellite.agent;

/**
 * The built-in pack: perception, movement, combat, survival, cartography and
 * the local memory/planning tools.
 *
 * <p>It is an extension like any other, which keeps {@code AgentBridge} free of
 * catalog knowledge and makes the core catalog replaceable in tests.</p>
 */
public final class CoreToolsExtension implements AgentExtension {

    @Override
    public String id() {
        return "core";
    }

    @Override
    public String description() {
        return "Percepcion, movimiento, combate, supervivencia, mapa y memoria";
    }

    @Override
    public void register(AgentExtensionContext context) {
        AgentTools.registerAll(context.registry(), context.remote(), context.memory(),
                context.planner(), context.goals());
    }
}
