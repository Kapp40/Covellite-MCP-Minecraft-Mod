package covellite.bridge;

import covellite.core.json.Json;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict tool catalog for the MCP server. Only tools that are actually
 * implemented by the mod are registered.
 *
 * <p>Descriptions are intentionally terse: the model re-reads the whole
 * catalog every turn, so every word costs latency. The "quick action" tools
 * (see {@link #isAwaited}) block inside the bridge until they finish, polling
 * {@code get_action_status} every 20ms itself, so the model needs a single
 * call per task instead of a polling loop of its own. {@code wait:false}
 * opts out and returns the raw {@code action_id} for manual polling. Chat
 * typed by the human is attached to any result as {@code new_chat}; the same
 * lines are also queued by {@link ChatRelay} and exposed by
 * {@code get_relayed_chat}, so a message is never lost between turns.</p>
 */
public final class ToolCatalog {
    private ToolCatalog() {}

    private static final String IGNORE_DOC =
            "Interrupt kinds to skip: enemy, chat, night, health, hazard.";
    private static final String INTERRUPTS_DOC =
            "Default true. false = never stop early.";

    public static List<McpServerFeatures.SyncToolSpecification> build(ModConnection connection, ChatRelay relay) {
        List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();

        // Perception
        add(tools, connection, "get_status",
                "Mod/bridge connection status.",
                Map.of(), List.of());
        add(tools, connection, "scan",
                "ONE-CALL SNAPSHOT: position, hp, food, held item, inventory totals and a human view: "
                        + "see:[\"tree 6m NE\",\"cow 9m W\",\"iron 12m S down\",\"player Bob 12m S\"], danger:[monsters, lava], "
                        + "night_in_s/dawn_in_s. Prefer this over separate get_* calls.",
                props("radius", number("Entity radius (1-64). Default 16."),
                        "view", integer("Block search radius for trees/ores/water (4-24). Default 16."),
                        "raw", bool("Also return the raw entity list as 'near'."),
                        "limit", integer("Max raw entities (1-32). Default 8."),
                        "blocks", stringArray("Block-id substrings to also list, e.g. [\"log\",\"ore\"]. Omit to skip blocks.")),
                List.of());
        add(tools, connection, "get_player_state",
                "Position, rotation, hp, food, air, mode.",
                Map.of(), List.of());
        add(tools, connection, "get_world_info",
                "Dimension, time, difficulty, weather, biome.",
                Map.of(), List.of());
        add(tools, connection, "get_player_list",
                "Online players with position and mode.",
                Map.of(), List.of());
        add(tools, connection, "get_inventory",
                "Non-empty inventory stacks.",
                Map.of(), List.of());
        add(tools, connection, "get_equipment",
                "Hands and armour.",
                Map.of(), List.of());
        add(tools, connection, "get_hotbar",
                "Hotbar slots and selected slot.",
                Map.of(), List.of());
        add(tools, connection, "get_nearby_entities",
                "Nearby entities, nearest first.",
                props("radius", number("1-64, default 16."),
                        "limit", integer("1-128, default 32.")),
                List.of());
        add(tools, connection, "get_nearby_blocks",
                "Nearby blocks grouped by id as {id:[[x,y,z],...]}, nearest first.",
                props("radius", number("1-16, default 8."),
                        "blocks", stringArray("Block-id substrings, e.g. [\"ore\"]."),
                        "limit", integer("1-256, default 64.")),
                List.of());
        add(tools, connection, "get_targeted_object",
                "Block or entity under the crosshair.",
                Map.of(), List.of());
        add(tools, connection, "get_entity_info",
                "Details of a nearby entity.",
                props("entity", string("UUID or name.")),
                List.of("entity"));
        add(tools, connection, "get_block_info",
                "Details of a block position.",
                props("x", integer("X"), "y", integer("Y"), "z", integer("Z")),
                List.of("x", "y", "z"));
        add(tools, connection, "get_chat_history",
                "Chat log. Not needed normally: new player chat arrives as new_chat in any result.",
                props("limit", integer("1-256, default 20."),
                        "since", integer("Only messages after this sequence.")),
                List.of());
        add(tools, connection, "get_recent_events",
                "Game events (chat, join, leave, death).",
                props("since", integer("Only events after this sequence."),
                        "limit", integer("1-256, default 32.")),
                List.of());
        addLocal(tools, "get_relayed_chat",
                "Human Minecraft chat relayed live, including lines typed between your turns. Returns "
                        + "{messages:[{seq,from,message,at}],last_seq}. Call it first when the user refers to "
                        + "something said in the game; pass the previous last_seq as 'since' to get only new lines.",
                props("since", integer("Only messages after this sequence. Omit for the newest."),
                        "limit", integer("1-256, default 20.")),
                List.of(),
                args -> relay.since(numArg(args, "since", 0L), (int) numArg(args, "limit", 20L)));
        add(tools, connection, "get_durability_report",
                "Durability of every damageable item carried or worn, with low-warning flag.",
                Map.of(), List.of());
        add(tools, connection, "get_threat_assessment",
                "Hostiles around the companion and nearest human, with fight/retreat recommendation.",
                props("radius", number("4-64, default 24.")),
                List.of());
        add(tools, connection, "get_companion_state",
                "Consolidated read-only snapshot: activity (idle/following/guarding/staying/working/recovering), "
                        + "active task, target, autonomy, stance, threats, inventory, last reflex and last interruption.",
                Map.of(), List.of());
        add(tools, connection, "protect_player",
                "Guard a human from their perspective: stays close, evaluates threats around the player, warns "
                        + "about creepers instead of blindly swinging, and only chases inside the protect radius.",
                props("player", string("Player name. Default: nearest human."),
                        "enabled", bool("false releases the guard. Default true."),
                        "radius", number("4-32, default 16. Threats inside it are engaged."),
                        "aggressiveness", string("defensivo | normal | agresivo.")),
                List.of());
        add(tools, connection, "stay",
                "Stay exactly here until another order. Distinct from guarding: never chases, only fights back "
                        + "at arm's reach.",
                props("x", number("Optional X. Default: current position."),
                        "y", number("Optional Y."),
                        "z", number("Optional Z.")),
                List.of());
        add(tools, connection, "guard_position",
                "Guard an area with a radius: engages threats inside it and never chases beyond radius+4. "
                        + "Distinct from stay.",
                props("x", number("Optional X. Default: current position."),
                        "y", number("Optional Y."),
                        "z", number("Optional Z."),
                        "radius", number("4-32, default 8.")),
                List.of());
        add(tools, connection, "leave_position",
                "Drop stay/guard role and return to the previous activity.",
                Map.of(), List.of());

        // Movement / control
        add(tools, connection, "move",
                "Move by raw input for N ticks. Blocks until done; returns final position.",
                props("forward", number("-1..1"),
                        "strafe", number("-1..1"),
                        "sprint", bool("Sprint."),
                        "ticks", integer("1-200, default 20."),
                        "wait", bool("Wait until finished. Default true.")),
                List.of());
        add(tools, connection, "look",
                "Set yaw/pitch instantly.",
                props("yaw", number("Degrees."), "pitch", number("-90..90.")),
                List.of("yaw", "pitch"));
        add(tools, connection, "look_at",
                "Look at a world position.",
                props("x", number("X"), "y", number("Y"), "z", number("Z")),
                List.of("x", "y", "z"));
        add(tools, connection, "jump",
                "Jump once.",
                Map.of(), List.of());
        add(tools, connection, "set_sneak",
                "Sneak on/off.",
                props("enabled", bool("Sneak state.")),
                List.of("enabled"));
        add(tools, connection, "stop",
                "Cancel movement and the current action.",
                Map.of(), List.of());
        add(tools, connection, "sleep",
                "Walk to the nearest bed and lie down. Blocks until the companion is asleep or it fails "
                        + "(no bed, not bedtime, bed occupied); returns the bed position.",
                props("radius", number("Search radius in blocks, 4-96, default 32.")),
                List.of());
        add(tools, connection, "follow",
                "Follow a player until stop. Returns immediately.",
                props("player", string("Player name."),
                        "distance", number("1-16, default 3.")),
                List.of("player"));
        add(tools, connection, "goto",
                "Walk to a position (x/z, y optional) with A* pathfinding. Blocks until arrived, failed or interrupted "
                        + "(state=interrupted + reason: player_chat, enemy_near, low_health, taking_damage, night_fell, hazards); returns progress.",
                props("x", number("X"), "y", number("Y"), "z", number("Z"),
                        "tolerance", number("0.5-8, default 1."),
                        "ignore", stringArray(IGNORE_DOC), "interrupts", bool(INTERRUPTS_DOC),
                        "wait", bool("Wait until finished. Default true.")),
                List.of("x", "z"));
        add(tools, connection, "get_action_status",
                "State of an action by id (only needed when wait=false).",
                props("id", string("Action id.")),
                List.of("id"));
        add(tools, connection, "cancel_action",
                "Cancel an action by id.",
                props("id", string("Action id.")),
                List.of("id"));
        add(tools, connection, "emergency_stop",
                "Cancel everything and lock control until reset_emergency_stop.",
                Map.of(), List.of());
        add(tools, connection, "reset_emergency_stop",
                "Unlock control after emergency_stop.",
                Map.of(), List.of());
        add(tools, connection, "send_chat",
                "Say something in chat, or run a /command.",
                props("message", string("Text or /command.")),
                List.of("message"));

        // Item use, combat and building
        add(tools, connection, "use_item",
                "Right-click with the held item, optionally on a block face.",
                props("hand", string("main (default) or offhand."),
                        "x", integer("Target X."),
                        "y", integer("Target Y."),
                        "z", integer("Target Z."),
                        "face", string("up (default), down, north, south, east, west.")),
                List.of());
        add(tools, connection, "attack",
                "Melee hit on a nearby entity (within reach).",
                props("entity", string("Name or UUID.")),
                List.of("entity"));
        add(tools, connection, "eat",
                "Eat food from the inventory.",
                props("slot", integer("Slot. Default: first food.")),
                List.of());
        add(tools, connection, "place_block",
                "Place a block from the inventory at x/y/z.",
                props("x", integer("X"), "y", integer("Y"), "z", integer("Z"),
                        "block", string("Block id substring. Default: first block item."),
                        "face", string("Support face. Default: auto."),
                        "replace", bool("Overwrite existing block.")),
                List.of("x", "y", "z"));
        add(tools, connection, "break_block",
                "Break one block within reach.",
                props("x", integer("X"), "y", integer("Y"), "z", integer("Z")),
                List.of("x", "y", "z"));
        add(tools, connection, "chop_tree",
                "Walk to the nearest natural tree (or the one at x/y/z), chop it with real break time and pick up the drops. "
                        + "Blocks until done; returns broken/gained/issue.",
                props("max_logs", integer("Default 64."),
                        "radius", integer("Search radius, default 16."),
                        "x", integer("Log X."),
                        "y", integer("Log Y."),
                        "z", integer("Log Z."),
                        "allow_player_blocks", bool("Also chop player-built logs. Default false."),
                        "ignore", stringArray(IGNORE_DOC), "interrupts", bool(INTERRUPTS_DOC),
                        "wait", bool("Wait until finished. Default true.")),
                List.of());
        add(tools, connection, "gather",
                "HIGH-LEVEL: walk to and collect N of a resource (wood, stone, iron, coal, diamond, sand, dirt, any block id). "
                        + "Picks the right tool, digs through safe obstacles, avoids lava, collects drops. "
                        + "Blocks until done; returns broken/gained/issue.",
                props("what", string("Resource: wood, stone, iron, coal, gold, diamond, copper, redstone, lapis, emerald, sand, gravel, dirt or a block id substring. Default wood."),
                        "count", integer("Blocks to break (1-256). Default 8."),
                        "radius", number("Search radius (4-32). Default 24."),
                        "timeout_ticks", integer("100-12000, default 3600."),
                        "allow_player_blocks", bool("Also take player-built logs. Default false."),
                        "ignore", stringArray(IGNORE_DOC), "interrupts", bool(INTERRUPTS_DOC),
                        "wait", bool("Wait until finished. Default true.")),
                List.of());
        add(tools, connection, "mine",
                "Like gather but defaults to stone. Use what='iron' etc. for ores.",
                props("what", string("Resource. Default stone."),
                        "ignore", stringArray(IGNORE_DOC), "interrupts", bool(INTERRUPTS_DOC),
                        "count", integer("Blocks to break (1-256). Default 8."),
                        "radius", number("Search radius (4-32). Default 24."),
                        "timeout_ticks", integer("100-12000, default 3600."),
                        "wait", bool("Wait until finished. Default true.")),
                List.of());
        add(tools, connection, "craft_item",
                "Craft an item from inventory ingredients (3x3 needs a table within 4 blocks).",
                props("item", string("Result id substring, e.g. 'stick'."),
                        "count", integer("Output quantity, default 1.")),
                List.of("item"));
        add(tools, connection, "craft",
                "HIGH-LEVEL: craft anything end to end from a phrase ('pico de hierro', 'iron_pickaxe'). "
                        + "Plans the whole recipe tree (planks, sticks, table...), places a table if needed and, "
                        + "if only logs are missing, gathers them first. Blocks until done; returns produced/steps or missing. "
                        + "Does not smelt.",
                props("item", string("Item phrase or id, e.g. 'pico de hierro', 'espada de piedra', 'chest'."),
                        "count", integer("Output quantity, default 1."),
                        "gather", bool("Gather missing logs automatically. Default true."),
                        "ignore", stringArray(IGNORE_DOC), "interrupts", bool(INTERRUPTS_DOC),
                        "wait", bool("Wait until finished. Default true.")),
                List.of("item"));
        add(tools, connection, "remember",
                "Save a named place (default: where you stand) in the world's persistent memory. Same name = update.",
                props("name", string("Place name, e.g. 'casa', 'mina de hierro'."),
                        "x", number("X. Default: current."),
                        "y", number("Y. Default: current."),
                        "z", number("Z. Default: current."),
                        "note", string("Optional note.")),
                List.of("name"));
        add(tools, connection, "recall",
                "Saved place by name, or all of them nearest first (with distance d).",
                props("name", string("Place name. Omit to list all.")),
                List.of());
        add(tools, connection, "forget",
                "Delete a saved place.",
                props("name", string("Place name.")),
                List.of("name"));
        add(tools, connection, "go",
                "Walk to a saved place by name (A*). Blocks until arrival; interrupts like goto.",
                props("name", string("Saved place name."),
                        "tolerance", number("0.5-8, default 2."),
                        "ignore", stringArray(IGNORE_DOC), "interrupts", bool(INTERRUPTS_DOC),
                        "wait", bool("Wait until arrival. Default true.")),
                List.of("name"));
        add(tools, connection, "set_mode",
                "Temperament: cauteloso (flees early, eats early, watches lava), valiente (fights, ignores night) or normal. "
                        + "Changes interrupt/auto-eat/auto-defend thresholds. Also toggles lively idle behaviour (greets, looks at you).",
                props("mode", string("cauteloso | valiente | normal. Omit to only read the current mode."),
                        "lively", bool("Idle gaze + greeting bow. Default true.")),
                List.of());
        add(tools, connection, "give",
                "Hand an inventory item to a player: walks to them if far, then drops it at their feet. "
                        + "Returns state gave|going.",
                props("item", string("Id substring, e.g. 'iron_ingot', or 'food'."),
                        "player", string("Player name. Default: nearest human."),
                        "count", integer("Default: whole stack.")),
                List.of("item"));
        add(tools, connection, "get_craftable",
                "Recipes craftable right now.",
                props("item", string("Result filter."),
                        "limit", integer("Default 40.")),
                List.of());
        add(tools, connection, "equip_armor",
                "Equip an armour piece from the inventory.",
                props("item", string("Armour id substring. Default: first piece.")),
                List.of());
        add(tools, connection, "select_hotbar",
                "Select hotbar slot 0-8.",
                props("slot", integer("0-8.")),
                List.of());
        add(tools, connection, "drop_item",
                "Drop items from the inventory.",
                props("slot", integer("0-35."),
                        "item", string("Id substring if no slot."),
                        "count", integer("Default: whole stack.")),
                List.of());
        add(tools, connection, "container_list",
                "List a chest/barrel within reach.",
                props("x", integer("X"), "y", integer("Y"), "z", integer("Z"),
                        "include_empty", bool("Include empty slots."),
                        "limit", integer("Default 64.")),
                List.of("x", "y", "z"));
        add(tools, connection, "container_take",
                "Take items from a container.",
                props("x", integer("X"), "y", integer("Y"), "z", integer("Z"),
                        "slot", integer("Container slot."),
                        "item", string("Id substring if no slot."),
                        "count", integer("Default: whole stack.")),
                List.of("x", "y", "z"));
        add(tools, connection, "container_put",
                "Put items into a container.",
                props("x", integer("X"), "y", integer("Y"), "z", integer("Z"),
                        "item", string("Id substring. Default: first item."),
                        "count", integer("Default: whole stack."),
                        "container_slot", integer("Destination slot.")),
                List.of("x", "y", "z"));
        add(tools, connection, "authorize_container",
                "Grant persistent permission for ONE chest/barrel. Empty take/put lists with all=true means "
                        + "full access. Without permission the high-level tools refuse to touch it.",
                props("x", integer("X"), "y", integer("Y"), "z", integer("Z"),
                        "name", string("Optional label, e.g. base-storage."),
                        "take", stringArray("Item ids Cove may take, e.g. [\"cobblestone\",\"torch\"]."),
                        "put", stringArray("Item ids Cove may deposit."),
                        "all", bool("Allow everything. Default true only when both lists are empty.")),
                List.of("x", "y", "z"));
        add(tools, connection, "list_container_permissions",
                "List every saved container permission for this world.",
                Map.of(), List.of());
        add(tools, connection, "revoke_container",
                "Remove the permission for one container.",
                props("x", integer("X"), "y", integer("Y"), "z", integer("Z")),
                List.of("x", "y", "z"));
        add(tools, connection, "find_container",
                "Nearby containers with their authorization and optional item filter. Read-only.",
                props("radius", integer("1-32, default 12."),
                        "item", string("Only containers holding this item id substring.")),
                List.of());
        add(tools, connection, "deposit_items",
                "Deposit one item type ONLY into a container authorized for it. Never reorganizes other slots.",
                props("item", string("Item id substring."),
                        "count", integer("Default: whole stack."),
                        "x", integer("Optional target X."), "y", integer("Optional Y."),
                        "z", integer("Optional Z."),
                        "radius", integer("Search radius when no coordinates. Default 16.")),
                List.of("item"));
        add(tools, connection, "restock",
                "Pull one item type from the nearest authorized container, up to count.",
                props("item", string("Item id substring."),
                        "count", integer("1-2304, default 64."),
                        "radius", integer("4-32, default 16.")),
                List.of("item"));
        add(tools, connection, "use_on_entity",
                "Use the held item on an entity (shears, bucket, name tag...).",
                props("entity", string("Name or UUID."),
                        "radius", integer("Default 8.")),
                List.of("entity"));
        add(tools, connection, "batch",
                "Run several tools in ONE call: actions=[{tool,params},...]. Instant tools run in order; "
                        + "a trailing move/goto is awaited. Use this to chain steps without extra turns.",
                props("actions", objectArray("[{tool:string, params:object}, ...]"),
                        "wait", bool("Await a trailing move/goto. Default true.")),
                List.of("actions"));
        add(tools, connection, "set_autopilot",
                "Toggle instant reflexes: chat orders, auto-eat, auto-defend.",
                props("enabled", bool("All at once."),
                        "chat", bool("Chat orders (veni, para, tala, come...)."),
                        "eat", bool("Auto-eat."),
                        "defend", bool("Auto-attack hostiles.")),
                List.of());
        add(tools, connection, "take_screenshot",
                "Screenshot from the companion's eyes. Blocks until it is ready; returns path/width/height "
                        + "or an error. Never blocks the game itself (the mod side is fully async).",
                props("timeout_ms", integer("1000-15000, default 6000. Fails the action if no frame renders."),
                        "wait", bool("Wait until finished. Default true.")),
                List.of());

        // Lifecycle
        add(tools, connection, "spawn", "Spawn the companion.", Map.of(), List.of());
        add(tools, connection, "despawn", "Remove the companion.", Map.of(), List.of());
        add(tools, connection, "respawn", "Revive the companion now.", Map.of(), List.of());

        return tools;
    }

    /**
     * Registers a tool served entirely by the bridge, with no round-trip to the
     * mod. Used for data the bridge already owns (the relayed chat queue), so
     * it keeps working even while Minecraft is closed.
     */
    private static void addLocal(List<McpServerFeatures.SyncToolSpecification> tools,
                                 String name,
                                 String description,
                                 Map<String, Object> properties,
                                 List<String> required,
                                 java.util.function.Function<Map<String, Object>, Object> handler) {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object", properties, required, false, null, null);
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(schema)
                .build();
        tools.add(new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            try {
                Map<String, Object> args = request.arguments() == null
                        ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(request.arguments());
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(Json.stringify(handler.apply(args)))))
                        .isError(false)
                        .build();
            } catch (Exception e) {
                return error(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }));
    }

    private static long numArg(Map<String, Object> args, String key, long fallback) {
        Object value = args.get(key);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static void add(List<McpServerFeatures.SyncToolSpecification> tools,
                            ModConnection connection,
                            String name,
                            String description,
                            Map<String, Object> properties,
                            List<String> required) {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object", properties, required, false, null, null);
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(schema)
                .build();
        tools.add(new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            try {
                Map<String, Object> args = request.arguments() == null
                        ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(request.arguments());
                // "wait" is a bridge-side option; the mod never sees it.
                Object waitFlag = args.remove("wait");
                boolean wait = !(waitFlag instanceof Boolean flag) || flag;
                Object result = wait && shouldWait(name, args)
                        ? connection.callAndWait(name, args, maxWaitMs(name, args))
                        : connection.call(name, args);
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(Json.stringify(result))))
                        .isError(false)
                        .build();
            } catch (ModConnection.ModException e) {
                return error(e.code() + ": " + e.getMessage());
            } catch (Exception e) {
                return error(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }));
    }

    /**
     * move/goto always finish on their own. A batch is awaited only when its
     * last step is one of them: a trailing follow never ends, so it must not
     * block the call.
     */
    private static boolean shouldWait(String name, Map<String, Object> args) {
        if (isAwaited(name)) {
            return true;
        }
        if ("batch".equals(name)
                && args.get("actions") instanceof List<?> actions
                && !actions.isEmpty()
                && actions.get(actions.size() - 1) instanceof Map<?, ?> last) {
            Object tool = last.get("tool");
            return tool instanceof String t && isAwaited(t);
        }
        return false;
    }

    /** Tools that always finish on their own, so the bridge can wait for them. */
    private static boolean isAwaited(String name) {
        return "move".equals(name) || "goto".equals(name) || "go".equals(name)
                || "chop_tree".equals(name) || "gather".equals(name) || "mine".equals(name)
                || "craft".equals(name) || "sleep".equals(name) || "take_screenshot".equals(name);
    }

    private static long maxWaitMs(String name, Map<String, Object> args) {
        if ("move".equals(name)) {
            double ticks = args.get("ticks") instanceof Number n ? n.doubleValue() : 20.0D;
            return (long) Math.min(200.0D, Math.max(1.0D, ticks)) * 50L + 4_000L;
        }
        if ("gather".equals(name) || "mine".equals(name) || "craft".equals(name)) {
            double ticks = args.get("timeout_ticks") instanceof Number n ? n.doubleValue() : 3600.0D;
            return (long) Math.min(12000.0D, Math.max(100.0D, ticks)) * 50L + 5_000L;
        }
        if ("chop_tree".equals(name)) {
            return 2400L * 50L + 5_000L;
        }
        if ("take_screenshot".equals(name)) {
            // Give it a bit more than the mod's own timeout_ms so the mod's
            // own "failed" result (rather than the bridge's generic
            // "still running" fallback) is what actually comes back.
            double timeoutMs = args.get("timeout_ms") instanceof Number n ? n.doubleValue() : 6000.0D;
            return (long) Math.min(15000.0D, Math.max(1000.0D, timeoutMs)) + 3_000L;
        }
        return 90_000L;
    }

    private static McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(message)))
                .isError(true)
                .build();
    }

    private static Map<String, Object> props(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) throw new IllegalArgumentException("odd property list");
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put((String) keyValuePairs[i], keyValuePairs[i + 1]);
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

    private static Map<String, Object> stringArray(String description) {
        return Map.of("type", "array", "description", description,
                "items", Map.of("type", "string"));
    }

    private static Map<String, Object> objectArray(String description) {
        return Map.of("type", "array", "description", description,
                "items", Map.of("type", "object"));
    }
}
