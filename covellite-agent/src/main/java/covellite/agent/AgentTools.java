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
                                   AgentPlanner planner,
                                   AgentGoals goals) {
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
        // Combat loadout and tactics
        // ----------------------------------------------------------
        add(registry, remote, "equip_best_weapon",
                "Equipa el mejor arma del inventario (espada, hacha o tridente) y devuelve el loadout.",
                schema(props(), List.of()));
        add(registry, remote, "combat_loadout",
                "Arma en mano, offhand, flechas, vida, comida y si conviene retirarse.",
                schema(props(), List.of()));
        add(registry, remote, "shield",
                "Equipa el escudo en la mano secundaria y empieza o deja de bloquear.",
                schema(props("enabled", bool("true = bloquear.")), List.of("enabled")));
        add(registry, remote, "use_consumable",
                "Usa la mejor cura disponible: manzana dorada, poción o comida.",
                schema(props("kind", string("heal, potion o any.")), List.of()));
        add(registry, remote, "evade",
                "Se retira del hostil mas cercano mientras zigzaguea.",
                schema(props(
                        "ticks", integer("1-200, por defecto 20."),
                        "radius", number("Radio de busqueda del hostil.")), List.of()));
        add(registry, remote, "shoot",
                "Dispara una flecha con arco a una entidad. Requiere arco y flechas.",
                schema(props(
                        "entity", string("Nombre o UUID de la entidad."),
                        "charge_ticks", integer("Ticks de carga, por defecto 22.")), List.of("entity")));

        // ----------------------------------------------------------
        // Survival
        // ----------------------------------------------------------
        add(registry, remote, "harvest_farm",
                "Cosecha los cultivos maduros alrededor y replanta si hay semillas.",
                schema(props(
                        "radius", integer("1-16, por defecto 6."),
                        "replant", bool("true = replantar.")), List.of()));
        add(registry, remote, "plant_seeds",
                "Planta semillas en la tierra de cultivo libre alrededor.",
                schema(props("radius", integer("1-16, por defecto 6.")), List.of()));
        add(registry, remote, "feed_animals",
                "Alimenta animales cercanos para que crien.",
                schema(props("radius", integer("1-24, por defecto 8.")), List.of()));
        add(registry, remote, "furnace_status",
                "Contenido de entrada, combustible y salida del horno mas cercano.",
                schema(props("radius", integer("1-16, por defecto 4.")), List.of()));
        add(registry, remote, "furnace_load",
                "Carga minerales y combustible en el horno cercano.",
                schema(props(
                        "item", string("Texto del id, por ejemplo iron_ore."),
                        "fuel", string("Texto del combustible. Omitir = carbon, tablones o troncos."),
                        "count", integer("Maximo a cargar, por defecto 64."),
                        "radius", integer("1-16, por defecto 4.")), List.of("item")));
        add(registry, remote, "furnace_collect",
                "Recoge lo fundido en el horno mas cercano.",
                schema(props("radius", integer("1-16, por defecto 4.")), List.of()));

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

        // ----------------------------------------------------------
        // Local tools: goals
        // ----------------------------------------------------------
        registry.register(new AgentProvider.ToolSpec("goal_create",
                        "Registra un objetivo persistente y sus pasos. Usalo cuando el pedido lleva mas de una accion.",
                        schema(props(
                                "goal", string("Objetivo en lenguaje natural."),
                                "steps", arrayOfStrings("Pasos concretos, en orden.")), List.of("goal"))),
                (name, args) -> {
                    List<String> steps = new ArrayList<>();
                    if (args.get("steps") instanceof List<?> raw) {
                        for (Object item : raw) {
                            steps.add(String.valueOf(item));
                        }
                    }
                    AgentGoals.Goal goal = goals.create(String.valueOf(args.getOrDefault("goal", "")), steps);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("id", goal.id());
                    out.put("steps", goal.steps().size());
                    return out;
                });

        registry.register(new AgentProvider.ToolSpec("goal_list",
                        "Lista los objetivos activos con su progreso y el paso siguiente.",
                        schema(props(), List.of())),
                (name, args) -> goals.status());

        registry.register(new AgentProvider.ToolSpec("goal_advance",
                        "Marca como hecho el paso siguiente del objetivo. Llamalo solo cuando el resultado esta verificado.",
                        schema(props("id", string("Id del objetivo.")), List.of("id"))),
                (name, args) -> {
                    AgentGoals.Goal goal = goals.advance(String.valueOf(args.getOrDefault("id", "")));
                    Map<String, Object> out = new LinkedHashMap<>();
                    if (goal == null) {
                        out.put("ok", false);
                        out.put("error", "unknown goal");
                        return out;
                    }
                    out.put("ok", true);
                    out.put("status", goal.status().name().toLowerCase(java.util.Locale.ROOT));
                    out.put("progress", goal.progressPercent());
                    out.put("next", goal.nextStep());
                    return out;
                });

        registry.register(new AgentProvider.ToolSpec("goal_complete",
                        "Cierra un objetivo como completado.",
                        schema(props("id", string("Id del objetivo.")), List.of("id"))),
                (name, args) -> Map.of("ok",
                        goals.complete(String.valueOf(args.getOrDefault("id", "")))));

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

    private static Map<String, Object> arrayOfStrings(String description) {
        return Map.of("type", "array", "description", description,
                "items", Map.of("type", "string"));
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
