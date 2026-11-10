package covellite.bridge;

/**
 * Contract of MCP tools exposed by the bridge. Only the Phase 1 movement and
 * perception tools are listed here; interaction and memory tools belong to
 * Phase 2.
 */
public final class McpToolContract {
    private McpToolContract() {}

    // Perception
    public static final String GET_STATUS = "get_status";
    public static final String GET_PLAYER_STATE = "get_player_state";
    public static final String GET_WORLD_INFO = "get_world_info";
    public static final String GET_PLAYER_LIST = "get_player_list";
    public static final String GET_INVENTORY = "get_inventory";
    public static final String GET_EQUIPMENT = "get_equipment";
    public static final String GET_HOTBAR = "get_hotbar";
    public static final String GET_NEARBY_ENTITIES = "get_nearby_entities";
    public static final String GET_NEARBY_BLOCKS = "get_nearby_blocks";
    public static final String GET_TARGETED_OBJECT = "get_targeted_object";
    public static final String GET_ENTITY_INFO = "get_entity_info";
    public static final String GET_BLOCK_INFO = "get_block_info";
    public static final String GET_CHAT_HISTORY = "get_chat_history";
    public static final String GET_RECENT_EVENTS = "get_recent_events";
    public static final String GET_SCREENSHOT = "get_screenshot";

    // Movement / control
    public static final String MOVE = "move";
    public static final String LOOK = "look";
    public static final String LOOK_AT = "look_at";
    public static final String JUMP = "jump";
    public static final String SET_SNEAK = "set_sneak";
    public static final String STOP = "stop";
    public static final String FOLLOW = "follow";
    public static final String GOTO = "goto";
    public static final String GET_ACTION_STATUS = "get_action_status";
    public static final String CANCEL_ACTION = "cancel_action";
    public static final String EMERGENCY_STOP = "emergency_stop";
}
