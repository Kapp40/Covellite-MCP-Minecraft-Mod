package covellite.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool catalog exposed to the internal model.
 *
 * <p>Every name registered here exists in the mod's {@code Dispatcher}, so a
 * tool that the model sees can always be executed. Local tools (memory and
 * planning) run inside the agent process and never touch the world.</p>
 */
public final class AgentTools {

    private AgentTools() {
    }

    /**
     * Registers the full catalog.
     *
     * @param registry      destination registry
     * @param remote        executor that performs world tools (runs them on the
     *                      Minecraft server thread)
     * @param memory        persistent memory, used by the local memory tools
     * @param planner       goal decomposer, used by {@code plan_goal}
     */
    public static void registerAll(AgentToolRegistry registry,
                                   AgentToolRegistry.ToolExecutor remote,
                                   AgentMemory memory,
                                   AgentPlanner planner) {
        // ----------------------------------------------------------
        // Perception
        // ----------------------------------------------------------
        add(registry, remote, "scan",
                "FOTO COMPLETA en una llamada: posicion, vida, comida, item en mano, inventario, "
                        + "que se ve alrededor y peligros. Usala antes de actuar.",
                schema(props(
                        "radius", number("Radio de entidades 1-64. Por defecto 16."),
                        "view", integer("Radio de busqueda de bloques 4-24. Por defecto 16."),
                        "raw", bool("Incluir la lista cruda de entidades."),
                        "limit", integer("Maximo de entidades crudas 1-32.")), List.of()));
        add(registry, remote, "get_companion_state",
                "Estado consolidado: actividad, tarea activa, objetivo, postura, amenazas, ultimo reflejo.",
                schema(props(), List.of()));
        add(registry, remote, "get_player_state",
                "Posicion, rotacion, vida, comida, aire y modo del companero.",
                schema(props(), List.of()));
        add(registry, remote, "get_world_info",
                "Dimension, hora, dificultad, clima y bioma.",
                schema(props(), List.of()));
        add(registry, remote, "get_inventory",
                "Stacks no vacios del inventario.", schema(props(), List.of()));
        add(registry, remote, "get_nearby_entities",
                "Entidades cercanas, la mas proxima primero.",
                schema(props(
                        "radius", number("1-64, por defecto 16."),
                        "limit", integer("1-128, por defecto 32.")), List.of()));
        add(registry, remote, "get_threat_assessment",
                "Hostiles alrededor y recomendacion de pelear o retirarse.",
                schema(props("radius", number("4-64, por defecto 24.")), List.of()));
        add(registry, remote, "get_durability_report",
                "Durabilidad de cada item con desgaste.", schema(props(), List.of()));
        add(registry, remote, "get_chat_history",
                "Historial de chat del servidor.",
                schema(props(
                        "limit", integer("1-256, por defecto 20."),
                        "since", integer("Solo mensajes posteriores a esta secuencia.")), List.of()));
        add(registry, remote, "get_recent_events",
                "Eventos del juego: chat, ingreso, salida, muerte.",
                schema(props(
                        "since", integer("Solo eventos posteriores a esta secuencia."),
                        "limit", integer("1-256, por defecto 32.")), List.of()));

        // ----------------------------------------------------------
        // Movement
        // ----------------------------------------------------------
        add(registry, remote, "move",
                "Mueve al companero durante N ticks con entrada de controles real.",
                schema(props(
                        "forward", number("-1 a 1."),
                        "strafe", number("-1 a 1."),
                        "sprint", bool("Correr."),
                        "ticks", integer("1-200, por defecto 20.")), List.of()));
        add(registry, remote, "goto",
                "Camina hasta una coordenada usando el pathfinder del mod.",
                schema(props(
                        "x", number("X destino."),
                        "y", number("Y destino. Omitir = cualquier altura."),
                        "z", number("Z destino."),
                        "tolerance", number("Tolerancia 0.5-8.")), List.of("x", "z")));
        add(registry, remote, "follow",
                "Sigue a un jugador manteniendo distancia.",
                schema(props(
                        "player", string("Nombre del jugador."),
                        "distance", number("Distancia a mantener.")), List.of("player")));
        add(registry, remote, "stay",
                "Se queda en el lugar actual hasta nueva orden.", schema(props(), List.of()));
        add(registry, remote, "stop",
                "Detiene la tarea y el movimiento actual.", schema(props(), List.of()));
        add(registry, remote, "jump", "Salta una vez.", schema(props(), List.of()));
        add(registry, remote, "set_sneak", "Agacha o levanta al companero.",
                schema(props("enabled", bool("true = agachado.")), List.of("enabled")));

        // ----------------------------------------------------------
        // Interaction, combat, survival
        // ----------------------------------------------------------
        add(registry, remote, "attack",
                "Ataca a la entidad indicada. Requiere estar a rango de golpe.",
                schema(props("entity", string("Nombre o UUID de la entidad.")), List.of("entity")));
        add(registry, remote, "use_item", "Usa el item en la mano.",
                schema(props("hand", string("main o off.")), List.of()));
        add(registry, remote, "eat", "Come para recuperar comida.",
                schema(props("slot", integer("Slot con comida. Omitir = buscar.")), List.of()));
        add(registry, remote, "sleep", "Busca una cama y duerme.", schema(props(), List.of()));
        add(registry, remote, "break_block", "Rompe un bloque en una posicion exacta.",
                schema(props(
                        "x", integer("X"), "y", integer("Y"), "z", integer("Z")),
                        List.of("x", "y", "z")));
        add(registry, remote, "place_block", "Coloca un bloque en una posicion exacta.",
                schema(props(
                        "x", integer("X"), "y", integer("Y"), "z", integer("Z"),
                        "block", string("Id del bloque, por ejemplo minecraft:cobblestone.")),
                        List.of("x", "y", "z", "block")));
        add(registry, remote, "mine", "Minera el material indicado alrededor.",
                schema(props(
                        "what", string("Material: stone, iron, diamond, wood..."),
                        "radius", number("Radio de busqueda.")), List.of()));
        add(registry, remote, "chop_tree", "Tala el arbol mas cercano.",
                schema(props(
                        "max_logs", integer("Maximo de troncos, por defecto 64."),
                        "radius", number("Radio de busqueda.")), List.of()));
        add(registry, remote, "craft_item", "Fabrica un item usando el inventario.",
                schema(props(
                        "item", string("Id o nombre del item."),
                        "count", integer("Cantidad, por defecto 1.")), List.of("item")));

        // ----------------------------------------------------------
        // Communication
        // ----------------------------------------------------------
        add(registry, remote, "send_chat",
                "Escribe un mensaje en el chat de Minecraft.",
                schema(props("message", string("Texto a decir.")), List.of("message")));

        // ----------------------------------------------------------
        // Local tools: memory and planning
        // ----------------------------------------------------------
        registry.register(new AgentProvider.ToolSpec("memory_remember",
                        "Guarda un recuerdo persistente. Usalo para lugares, hechos, preferencias y lecciones.",
                        schema(props(
                                "kind", string("conversation, episodic, semantic, preference, goal, error o journal."),
                                "text", string("Contenido del recuerdo.")), List.of("text"))),
                (name, args) -> {
                    String raw = String.valueOf(args.getOrDefault("kind", "episodic"));
                    AgentMemory.Kind kind;
                    try {
                        kind = AgentMemory.Kind.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
                    } catch (RuntimeException e) {
                        kind = AgentMemory.Kind.EPISODIC;
                    }
                    AgentMemory.Entry entry = memory.remember(kind,
                            String.valueOf(args.getOrDefault("text", "")), List.of("agent"));
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("stored", entry != null);
                    out.put("id", entry == null ? "" : entry.id());
                    return out;
                });

        registry.register(new AgentProvider.ToolSpec("memory_search",
                        "Busca recuerdos por palabras clave.",
                        schema(props(
                                "query", string("Que buscar."),
                                "limit", integer("Maximo de resultados.")), List.of("query"))),
                (name, args) -> {
                    List<AgentMemory.Entry> found = memory.search(
                            String.valueOf(args.getOrDefault("query", "")), intArg(args, "limit", 5));
                    List<Object> items = new ArrayList<>();
                    for (AgentMemory.Entry entry : found) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", entry.id());
                        item.put("kind", entry.kind().name().toLowerCase(java.util.Locale.ROOT));
                        item.put("text", entry.text());
                        items.add(item);
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("results", items);
                    out.put("count", items.size());
                    return out;
                });

        registry.register(new AgentProvider.ToolSpec("memory_forget",
                        "Elimina un recuerdo por id.",
                        schema(props("id", string("Id del recuerdo.")), List.of("id"))),
                (name, args) -> Map.of("removed",
                        memory.forget(String.valueOf(args.getOrDefault("id", "")))));

        registry.register(new AgentProvider.ToolSpec("plan_goal",
                        "Descompone un objetivo complejo en pasos concretos y lo guarda como objetivo activo.",
                        schema(props("goal", string("Objetivo en lenguaje natural.")), List.of("goal"))),
                (name, args) -> {
                    try {
                        List<String> steps = planner.decompose(String.valueOf(args.getOrDefault("goal", "")));
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("steps", new ArrayList<Object>(steps));
                        out.put("count", steps.size());
                        return out;
                    } catch (ProviderException e) {
                        throw new IllegalStateException(e.getMessage(), e);
                    }
                });
    }

    private static void add(AgentToolRegistry registry, AgentToolRegistry.ToolExecutor remote,
                            String name, String description, Map<String, Object> inputSchema) {
        registry.register(new AgentProvider.ToolSpec(name, description, inputSchema), remote);
    }

    // ------------------------------------------------------------------
    // Schema helpers
    // ------------------------------------------------------------------

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "object");
        map.put("properties", properties);
        if (!required.isEmpty()) {
            map.put("required", new ArrayList<Object>(required));
        }
        return map;
    }

    private static Map<String, Object> props(Object... keyValuePairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValuePairs.length; i += 2) {
            map.put(String.valueOf(keyValuePairs[i]), keyValuePairs[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> number(String description) {
        return Map.of("type", "number", "description", description);
    }

    private static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    private static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    private static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static int intArg(Map<String, Object> args, String key, int fallback) {
        Object value = args.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
