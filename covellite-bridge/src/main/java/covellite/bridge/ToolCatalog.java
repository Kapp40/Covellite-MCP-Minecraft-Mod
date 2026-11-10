package covellite.bridge;

import covellite.core.json.Json;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Strict tool catalog for the MCP server. Only tools that are actually
 * implemented by the mod are registered. Phase 2 interaction/memory tools are
 * deliberately absent.
 */
public final class ToolCatalog {
    private ToolCatalog() {}

    public static List<McpServerFeatures.SyncToolSpecification> build(ModConnection connection) {
        List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();

        // Perception
        add(tools, connection, "get_status",
                "Returns mod/bridge connection status and whether the local player is the configured AI player.",
                Map.of(), List.of());
        add(tools, connection, "get_player_state",
                "Returns the AI player's position, rotation, health, food, air and game mode.",
                Map.of(), List.of());
        add(tools, connection, "get_world_info",
                "Returns dimension, time, difficulty, weather and biome.",
                Map.of(), List.of());
        add(tools, connection, "get_player_list",
                "Lists players visible in the tab list with latency and game mode.",
                Map.of(), List.of());
        add(tools, connection, "get_inventory",
                "Returns non-empty inventory stacks with slot, item id, count and damage.",
                Map.of(), List.of());
        add(tools, connection, "get_equipment",
                "Returns mainhand, offhand and armour equipment.",
                Map.of(), List.of());
        add(tools, connection, "get_hotbar",
                "Returns the nine hotbar slots and the selected slot.",
                Map.of(), List.of());
        add(tools, connection, "get_nearby_entities",
                "Returns nearby entities sorted by distance.",
                props("radius", number("Search radius in blocks (1-64). Default 16."),
                        "limit", integer("Maximum entities (1-128). Default 32.")),
                List.of());
        add(tools, connection, "get_nearby_blocks",
                "Returns nearby non-air blocks within a bounded radius.",
                props("radius", number("Search radius in blocks (1-16). Default 8."),
                        "blocks", stringArray("Optional block-id substrings, e.g. [\"ore\", \"chest\"]."),
                        "limit", integer("Maximum blocks (1-256). Default 64.")),
                List.of());
        add(tools, connection, "get_targeted_object",
                "Returns the block or entity currently targeted by the crosshair.",
                Map.of(), List.of());
        add(tools, connection, "get_entity_info",
                "Returns details for a nearby entity by UUID or exact name.",
                props("entity", string("Entity UUID or display name.")),
                List.of("entity"));
        add(tools, connection, "get_block_info",
                "Returns details for a loaded block position.",
                props("x", integer("Block X."), "y", integer("Block Y."), "z", integer("Block Z.")),
                List.of("x", "y", "z"));
        add(tools, connection, "get_chat_history",
                "Returns recent received chat/system messages.",
                props("limit", integer("Maximum messages (1-256). Default 20.")),
                List.of());
        add(tools, connection, "get_recent_events",
                "Returns logged events with sequence numbers after a given sequence.",
                props("since", integer("Return events with sequence greater than this. Default 0."),
                        "limit", integer("Maximum events (1-256). Default 32.")),
                List.of());

        // Movement / control
        add(tools, connection, "move",
                "Moves for a fixed number of client ticks. Returns an action id immediately.",
                props("forward", number("Forward impulse -1..1."),
                        "strafe", number("Left/right impulse -1..1."),
                        "sprint", bool("Whether to sprint."),
                        "ticks", integer("Duration in client ticks (1-200). Default 20.")),
                List.of());
        add(tools, connection, "look",
                "Sets yaw and pitch immediately. Returns an action id.",
                props("yaw", number("Yaw in degrees."), "pitch", number("Pitch in degrees (-90..90).")),
                List.of("yaw", "pitch"));
        add(tools, connection, "look_at",
                "Looks at a world position. Returns an action id.",
                props("x", number("Target X."), "y", number("Target Y."), "z", number("Target Z.")),
                List.of("x", "y", "z"));
        add(tools, connection, "jump",
                "Makes the AI player jump. Returns an action id.",
                Map.of(), List.of());
        add(tools, connection, "set_sneak",
                "Enables or disables sneaking until changed.",
                props("enabled", bool("Desired sneak state.")),
                List.of("enabled"));
        add(tools, connection, "stop",
                "Cancels all live actions and clears movement. Does not set the emergency latch.",
                Map.of(), List.of());
        add(tools, connection, "follow",
                "Follows a named player, keeping roughly the requested distance.",
                props("player", string("Exact player name to follow."),
                        "distance", number("Desired distance in blocks (1-16). Default 3.")),
                List.of("player"));
        add(tools, connection, "goto",
                "Walks toward a world position with a simple straight-line controller.",
                props("x", number("Target X."), "y", number("Target Y."), "z", number("Target Z."),
                        "tolerance", number("Horizontal tolerance in blocks (0.5-8). Default 1.")),
                List.of("x", "y", "z"));
        add(tools, connection, "get_action_status",
                "Returns the state of an action by id.",
                props("id", string("Action id returned by a control tool.")),
                List.of("id"));
        add(tools, connection, "cancel_action",
                "Cancels a queued or running action by id.",
                props("id", string("Action id to cancel.")),
                List.of("id"));
        add(tools, connection, "emergency_stop",
                "Immediately cancels everything and latches emergency stop until reset.",
                Map.of(), List.of());
        add(tools, connection, "reset_emergency_stop",
                "Clears the emergency-stop latch so new actions can be submitted.",
                Map.of(), List.of());
        add(tools, connection, "send_chat",
                "Sends a chat message or a /command as the AI player.",
                props("message", string("Message text, or a command starting with '/'.")),
                List.of("message"));

        return tools;
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
                Object result = connection.call(name, request.arguments());
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

    private static McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(message)))
                .isError(true)
                .build();
    }

    private static Map<String, Object> props(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) throw new IllegalArgumentException("odd property list");
        Map<String, Object> map = new java.util.LinkedHashMap<>();
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
}
