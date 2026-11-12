package com.covelliteai.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import com.covelliteai.CovelliteAI;

import covellite.core.dispatch.Dispatcher;
import covellite.core.event.Event;
import covellite.core.event.EventLog;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * Request handlers that back the MCP tool catalog.
 *
 * <p>All handlers run on the server thread (the dispatcher is pumped from
 * {@code ServerTickEvent.Post}), so they may safely touch the world. Control
 * handlers only write the same input fields a client would, and the vanilla
 * movement code performs the actual motion.</p>
 */
public final class BridgeApi {

    private static final double DEFAULT_TOLERANCE = 1.0D;

    private final CompanionManager companion;
    private final ChatLog chatLog;
    private final EventLog events;
    private final Map<String, Map<String, Object>> actions = new ConcurrentHashMap<>();
    private long nextActionId = 1L;
    private Task activeTask;
    private boolean emergency;

    // Smart interrupts (hito 4): armed per task by goto / gather / mine / chop_tree.
    /**
     * Temperament (hito 6): the thresholds behind interrupts, auto-eat and
     * auto-defend. {@code normal} keeps the pre-hito-6 numbers.
     */
    private record Stance(String name, double enemyRange, float lowHp, float damageDrop,
                          int eatBelow, float defendMinHp, boolean nightInterrupt, boolean lavaWatch) {}

    private static final Stance STANCE_NORMAL = new Stance("normal", 7.0D, 8.0F, 4.0F, 14, 6.0F, true, false);
    private static final Stance STANCE_CAUTIOUS = new Stance("cauteloso", 12.0D, 12.0F, 3.0F, 17, 10.0F, true, true);
    private static final Stance STANCE_BRAVE = new Stance("valiente", 4.0D, 5.0F, 7.0F, 9, 3.0F, false, false);
    private Stance stance = STANCE_NORMAL;
    private boolean interruptOn;
    private Set<String> interruptIgnore = Set.of();
    private long interruptChatBase;
    private boolean interruptStartNight;
    private float interruptHp;
    private Map<String, Object> interruptExtra;

    // Persistent memory (hito 5) and the recipe index used by auto-crafting.
    private WorldMemory worldMemory;
    private MinecraftServer memoryServer;
    private Map<Item, List<RecipeHolder<CraftingRecipe>>> recipeIndex;
    private MinecraftServer recipeIndexServer;

    /** True while {@code batch} runs its inner calls, so chat is attached only once at the end. */
    private boolean inBatch;
    /** Last chat sequence already pushed to the model inside a tool result (-1 = not initialised). */
    private long chatPushed = -1L;

    /** Reference to the dispatcher, used by the {@code batch} tool. */
    private Dispatcher dispatcher;

    // ------------------------------------------------------------------
    // Autopilot / reflexes
    //
    // The companion should not need a full model round-trip to obey a simple
    // order such as "veni" or "para". These flags drive a per-tick reflex
    // loop that works even when no MCP client is connected.
    // ------------------------------------------------------------------
    private boolean reflexChat = true;
    private boolean autoEat = true;
    private boolean autoDefend = false;
    private boolean autopilotReady = false;
    private long autopilotTicks = 0L;
    private long lastChatSequence = 0L;

    // Combat: automatic self-defence against hostiles, plus "defendeme" which
    // guards another player. Combat never touches the chat.
    private String defendPlayer = null;
    private Entity combatTarget = null;
    private long combatTargetDeadline = 0L;
    private long lastCombatSwing = 0L;
    private long lastHurtTick = -1000L;
    private float lastHpSeen = 20.0F;
    private boolean combatDriving = false;
    private long combatJumpTick = 0L;

    // Persistent follow, so a chat line or a finished task does not break it.
    private String followTarget = null;
    private double followDistance = 3.0D;
    private boolean followResume = false;
    private long followResumeAt = 0L;

    // Idle chatter: keeps the companion alive on screen instead of going silent.
    private long lastIdleChatTick = 0L;
    private long lastDefendReply = -10000L;
    private long lastGiveAt = -10000L;

    // Stuck detector: real displacement while a movement task is driving.
    private double stuckLastX;
    private double stuckLastZ;
    private long stuckSinceTick = -1L;
    private int stuckRecoveries;
    private long lastStuckNotice = -10000L;

    // Preventive durability: warn once per tool, swap to a compatible spare.
    private final Set<Item> warnedTools = new HashSet<>();
    private long lastDurabilityNotice = -10000L;

    // Inventory capacity during gathering.
    private boolean inventoryFullWarned;

    // Welcome protocol.
    private boolean welcomed;
    private long lastWelcomeTick = -10000L;

    // Personality (hito 6)
    private boolean lively = true;
    private int greetTicks;
    private boolean sneakHeld;
    private String pendingGiveItem;
    private int pendingGiveCount;
    private String pendingGivePlayer;
    private long pendingGiveDeadline;

    public BridgeApi(CompanionManager companion, ChatLog chatLog, EventLog events) {
        this.companion = companion;
        this.chatLog = chatLog;
        this.events = events;
    }

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

    public void register(Dispatcher dispatcher) {
        this.dispatcher = dispatcher;
        dispatcher.setDecorator(this::decorate);
        // Perception
        dispatcher.register("scan", this::scan);
        dispatcher.register("get_player_state", params -> playerState());
        dispatcher.register("get_world_info", params -> worldInfo());
        dispatcher.register("get_player_list", params -> playerList());
        dispatcher.register("get_inventory", params -> inventory());
        dispatcher.register("get_hotbar", params -> hotbar());
        dispatcher.register("get_equipment", params -> equipment());
        dispatcher.register("get_nearby_entities", this::nearbyEntities);
        dispatcher.register("get_durability_report", this::durabilityReport);
        dispatcher.register("get_threat_assessment", this::threatAssessment);
        dispatcher.register("get_nearby_blocks", this::nearbyBlocks);
        dispatcher.register("get_block_info", this::blockInfo);
        dispatcher.register("get_entity_info", this::entityInfo);
        dispatcher.register("get_targeted_object", params -> targetedObject());
        dispatcher.register("get_chat_history", this::chatHistory);
        dispatcher.register("get_recent_events", this::recentEvents);

        // Control
        dispatcher.register("move", this::move);
        dispatcher.register("look", this::look);
        dispatcher.register("look_at", this::lookAt);
        dispatcher.register("jump", this::jump);
        dispatcher.register("set_sneak", this::setSneak);
        dispatcher.register("stop", params -> stop());
        dispatcher.register("goto", this::gotoBlock);
        dispatcher.register("follow", this::follow);
        dispatcher.register("send_chat", this::sendChat);
        dispatcher.register("use_item", this::useItem);
        dispatcher.register("sleep", this::sleep);
        dispatcher.register("attack", this::attack);
        dispatcher.register("eat", this::eat);
        dispatcher.register("place_block", this::placeBlock);
        dispatcher.register("break_block", this::breakBlock);
        dispatcher.register("chop_tree", this::chopTree);
        dispatcher.register("gather", params -> gatherWith(params, "wood"));
        dispatcher.register("mine", params -> gatherWith(params, "stone"));
        dispatcher.register("craft_item", this::craftItem);
        dispatcher.register("craft", this::craftAuto);
        dispatcher.register("remember", this::remember);
        dispatcher.register("recall", this::recall);
        dispatcher.register("forget", this::forget);
        dispatcher.register("go", this::goToPlace);
        dispatcher.register("set_mode", this::setMode);
        dispatcher.register("give", this::give);
        dispatcher.register("get_craftable", this::getCraftable);
        dispatcher.register("equip_armor", this::equipArmor);
        dispatcher.register("select_hotbar", this::selectHotbar);
        dispatcher.register("drop_item", this::dropItem);
        dispatcher.register("container_list", this::containerList);
        dispatcher.register("container_take", this::containerTake);
        dispatcher.register("container_put", this::containerPut);
        dispatcher.register("use_on_entity", this::useOnEntity);
        dispatcher.register("take_screenshot", this::takeScreenshot);
        dispatcher.register("batch", this::batch);
        dispatcher.register("set_autopilot", this::setAutopilot);
        dispatcher.register("get_action_status", this::actionStatus);
        dispatcher.register("cancel_action", this::cancelAction);
        dispatcher.register("emergency_stop", params -> emergencyStop());
        dispatcher.register("reset_emergency_stop", params -> resetEmergencyStop());

        // Lifecycle
        dispatcher.register("spawn", this::spawnCompanion);
        dispatcher.register("despawn", this::despawnCompanion);
        dispatcher.register("respawn", this::respawnCompanion);
    }

    // ------------------------------------------------------------------
    // Per-tick task driver
    // ------------------------------------------------------------------

    public void tick(MinecraftServer server) {
        autopilotTick(server);
        if (this.activeTask == null) {
            return;
        }
        CompanionPlayer player = this.companion.player();
        if (player == null) {
            finishTask("cancelled", "companion_missing");
            return;
        }
        String interruptReason = checkInterrupt(player);
        if (interruptReason != null) {
            finishTask("interrupted", interruptReason);
            return;
        }
        try {
            if (this.activeTask.tick(player, server)) {
                finishTask("completed", null);
            }
        } catch (RuntimeException e) {
            finishTask("failed", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Perception
    // ------------------------------------------------------------------

    private Map<String, Object> playerState() {
        CompanionPlayer player = requirePlayer();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("dim", shortName(player.serverLevel().dimension().location().toString()));
        map.put("x", round1(player.getX()));
        map.put("y", round1(player.getY()));
        map.put("z", round1(player.getZ()));
        map.put("yaw", round1(player.getYRot()));
        map.put("pitch", round1(player.getXRot()));
        map.put("hp", round1(player.getHealth()));
        map.put("food", player.getFoodData().getFoodLevel());
        map.put("air", player.getAirSupply());
        map.put("mode", player.gameMode.getGameModeForPlayer().getName());
        map.put("ground", player.onGround());
        map.put("alive", player.isAlive());
        map.put("busy", this.activeTask != null);
        return map;
    }

    /**
     * One-call snapshot so the model does not need several perception calls:
     * position, vitals, held item, inventory totals, nearby entities and,
     * when {@code blocks} is given, matching nearby blocks.
     */
    private Map<String, Object> scan(Map<String, Object> params) {
        CompanionPlayer player = requirePlayer();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("x", round1(player.getX()));
        out.put("y", round1(player.getY()));
        out.put("z", round1(player.getZ()));
        out.put("hp", round1(player.getHealth()));
        out.put("food", player.getFoodData().getFoodLevel());
        ItemStack held = player.getMainHandItem();
        out.put("held", held.isEmpty() ? "-"
                : shortName(BuiltInRegistries.ITEM.getKey(held.getItem()).toString()) + " x" + held.getCount());
        out.put("time", (int) (player.serverLevel().getDayTime() % 24000L));
        out.put("busy", this.activeTask != null);

        Map<String, Object> inv = new LinkedHashMap<>();
        for (ItemStack stack : player.getInventory().items) {
            if (stack.isEmpty()) {
                continue;
            }
            String id = shortName(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            Object previous = inv.get(id);
            inv.put(id, (previous instanceof Integer total ? total : 0) + stack.getCount());
        }
        out.put("inv", inv);

        double radius = clamp(num(params, "radius", 16.0D), 1.0D, 64.0D);
        int limit = (int) clamp(num(params, "limit", 8.0D), 1.0D, 32.0D);
        List<Entity> found = player.serverLevel().getEntities(
                player, player.getBoundingBox().inflate(radius), e -> e != player);
        found.sort((a, b) -> Double.compare(a.distanceToSqr(player), b.distanceToSqr(player)));
        List<Map<String, Object>> near = new ArrayList<>();
        for (Entity entity : found) {
            if (near.size() >= limit) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("t", shortName(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()));
            if (entity instanceof ServerPlayer other) {
                item.put("n", other.getGameProfile().getName());
            } else if (entity.hasCustomName()) {
                item.put("n", entity.getName().getString());
            }
            item.put("p", List.of(round1(entity.getX()), round1(entity.getY()), round1(entity.getZ())));
            item.put("d", round1(Math.sqrt(entity.distanceToSqr(player))));
            near.add(item);
        }
        if (bool(params, "raw", false)) {
            out.put("near", near);
        }
        out.putAll(humanView(player, params));
        out.put("stance", this.stance.name());
        List<String> placesNearby = nearbyPlaces(player);
        if (!placesNearby.isEmpty()) {
            out.put("places", placesNearby);
        }
        if (params.containsKey("blocks")) {
            out.put("blocks", nearbyBlocks(params).get("blocks"));
        }
        return out;
    }

    /**
     * Adds unread chat from other players to every tool result, so the model
     * sees what the human said without having to ask for the history.
     */
    private Object decorate(String method, Object result) {
        if (this.inBatch || "get_action_status".equals(method)
                || !(result instanceof Map<?, ?> source)) {
            return result;
        }
        long latest = this.chatLog.latestSequence();
        if (this.chatPushed < 0L) {
            // Ignore everything said before the first tool call.
            this.chatPushed = latest;
            return result;
        }
        if (latest <= this.chatPushed) {
            return result;
        }
        List<ChatLog.Entry> fresh = this.chatLog.since(this.chatPushed, 8);
        long cursor = this.chatPushed;
        List<Map<String, Object>> lines = new ArrayList<>();
        for (ChatLog.Entry entry : fresh) {
            if (entry.sequence() <= this.chatPushed) {
                continue;
            }
            cursor = Math.max(cursor, entry.sequence());
            if (entry.fromCompanion() || !"chat".equals(entry.type())) {
                continue;
            }
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("from", String.valueOf(entry.sender()));
            line.put("msg", String.valueOf(entry.message()));
            lines.add(line);
        }
        this.chatPushed = fresh.size() < 8 ? Math.max(cursor, latest) : cursor;
        if (lines.isEmpty()) {
            return result;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        out.put("new_chat", lines);
        return out;
    }

    private static final Set<String> ORE_LABELS =
            Set.of("coal", "iron", "gold", "copper", "redstone", "lapis", "emerald", "diamond");

    /** Block label used by the human-readable scan, or null when it is not interesting. */
    private static String viewLabel(BlockState state) {
        if (state.is(BlockTags.LOGS)) {
            return "tree";
        }
        if (!state.getFluidState().isEmpty()) {
            if (state.getFluidState().is(FluidTags.LAVA)) {
                return "lava";
            }
            return state.getFluidState().isSource() ? "water" : null;
        }
        if (state.is(BlockTags.COAL_ORES)) return "coal";
        if (state.is(BlockTags.IRON_ORES)) return "iron";
        if (state.is(BlockTags.GOLD_ORES)) return "gold";
        if (state.is(BlockTags.COPPER_ORES)) return "copper";
        if (state.is(BlockTags.REDSTONE_ORES)) return "redstone";
        if (state.is(BlockTags.LAPIS_ORES)) return "lapis";
        if (state.is(BlockTags.EMERALD_ORES)) return "emerald";
        if (state.is(BlockTags.DIAMOND_ORES)) return "diamond";
        if (state.is(Blocks.CRAFTING_TABLE)) return "crafting_table";
        if (state.is(Blocks.CHEST) || state.is(Blocks.BARREL)) return "chest";
        if (state.is(Blocks.FURNACE)) return "furnace";
        return null;
    }

    /** An ore counts as visible when at least one side touches something see-through. */
    private static boolean exposed(ServerLevel level, BlockPos pos) {
        for (Direction side : Direction.values()) {
            BlockPos next = pos.relative(side);
            if (level.isLoaded(next) && !level.getBlockState(next).canOcclude()) {
                return true;
            }
        }
        return false;
    }

    private static String compass(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dz, dx)); // 0 = east, 90 = south
        String[] names = {"E", "SE", "S", "SW", "W", "NW", "N", "NE"};
        int index = (int) Math.round((((angle % 360.0D) + 360.0D) % 360.0D) / 45.0D) % 8;
        return names[index];
    }

    private static String describe(String label, double dx, double dy, double dz) {
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        StringBuilder text = new StringBuilder(label).append(' ').append(Math.round(dist)).append('m');
        if (Math.abs(dx) + Math.abs(dz) >= 2.0D) {
            text.append(' ').append(compass(dx, dz));
        }
        if (dy >= 3.0D) {
            text.append(" up");
        } else if (dy <= -3.0D) {
            text.append(" down");
        }
        return text.toString();
    }

    /**
     * Compact, human-style surroundings: {@code see} ("tree 6m NE", "cow 9m W",
     * "iron 12m S down", "player Steve 12m S"), {@code danger} (nearby monsters,
     * lava) and the time left until night or dawn.
     */
    private Map<String, Object> humanView(CompanionPlayer player, Map<String, Object> params) {
        ServerLevel level = player.serverLevel();
        int view = (int) clamp(num(params, "view", 16.0D), 4.0D, 24.0D);
        double entityRadius = clamp(num(params, "radius", 16.0D), 1.0D, 64.0D);
        Vec3 here = player.position();
        BlockPos origin = player.blockPosition();

        Map<String, String> blocks = new LinkedHashMap<>();
        Map<String, Double> blockDistance = new LinkedHashMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-view, -view, -view), origin.offset(view, view, view))) {
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            String label = viewLabel(state);
            if (label == null) {
                continue;
            }
            double d2 = Vec3.atCenterOf(pos).distanceToSqr(here);
            Double known = blockDistance.get(label);
            if (known != null && known <= d2) {
                continue;
            }
            if (ORE_LABELS.contains(label) && !exposed(level, pos)) {
                continue;
            }
            blockDistance.put(label, d2);
            blocks.put(label, describe(label,
                    pos.getX() + 0.5D - here.x, pos.getY() - here.y, pos.getZ() + 0.5D - here.z));
        }

        Map<String, String> animals = new LinkedHashMap<>();
        Map<String, Double> animalDistance = new LinkedHashMap<>();
        List<Object[]> monsters = new ArrayList<>();
        int itemCount = 0;
        double itemNearest = Double.MAX_VALUE;
        String itemWhere = null;
        for (Entity entity : level.getEntities(player,
                player.getBoundingBox().inflate(entityRadius), e -> e != player)) {
            if (!entity.isAlive() || entity instanceof ServerPlayer) {
                continue;
            }
            double dx = entity.getX() - here.x;
            double dy = entity.getY() - here.y;
            double dz = entity.getZ() - here.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (entity instanceof ItemEntity item) {
                if (d2 <= 64.0D) {
                    itemCount += item.getItem().getCount();
                    if (d2 < itemNearest) {
                        itemNearest = d2;
                        itemWhere = describe("items", dx, dy, dz);
                    }
                }
                continue;
            }
            String type = shortName(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            if (entity instanceof net.minecraft.world.entity.monster.Monster) {
                monsters.add(new Object[] {d2, describe(type, dx, dy, dz)});
            } else if (entity instanceof LivingEntity) {
                Double known = animalDistance.get(type);
                if (known == null || d2 < known) {
                    animalDistance.put(type, d2);
                    animals.put(type, describe(type, dx, dy, dz));
                }
            }
        }
        monsters.sort((a, b) -> Double.compare((Double) a[0], (Double) b[0]));

        List<String> see = new ArrayList<>();
        for (ServerPlayer other : level.getServer().getPlayerList().getPlayers()) {
            if (other == player) {
                continue;
            }
            String name = other.getGameProfile().getName();
            if (other.serverLevel() != level) {
                see.add("player " + name + " (other dimension)");
            } else {
                see.add(describe("player " + name, other.getX() - here.x,
                        other.getY() - here.y, other.getZ() - here.z));
            }
        }
        for (Map.Entry<String, String> entry : blocks.entrySet()) {
            if (!"lava".equals(entry.getKey())) {
                see.add(entry.getValue());
            }
        }
        int shown = 0;
        for (String text : animals.values()) {
            if (shown++ >= 5) {
                break;
            }
            see.add(text);
        }
        if (itemWhere != null) {
            see.add(itemWhere + " (" + itemCount + " total)");
        }

        List<String> danger = new ArrayList<>();
        for (int i = 0; i < monsters.size() && i < 4; i++) {
            danger.add((String) monsters.get(i)[1]);
        }
        Double lavaDistance = blockDistance.get("lava");
        if (lavaDistance != null && lavaDistance <= 36.0D) {
            danger.add(blocks.get("lava"));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("see", see);
        if (!danger.isEmpty()) {
            out.put("danger", danger);
        }
        long t = level.getDayTime() % 24000L;
        boolean night = isNight(level);
        out.put("day", !night);
        if (night) {
            out.put("dawn_in_s", ((23000L - t + 24000L) % 24000L) / 20L);
        } else {
            out.put("night_in_s", ((13000L - t + 24000L) % 24000L) / 20L);
        }
        return out;
    }

    private Map<String, Object> worldInfo() {
        CompanionPlayer player = requirePlayer();
        ServerLevel level = player.serverLevel();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("dimension", level.dimension().location().toString());
        map.put("game_time", level.getGameTime());
        map.put("day_time", level.getDayTime());
        map.put("difficulty", level.getDifficulty().getKey());
        map.put("raining", level.isRaining());
        map.put("thundering", level.isThundering());
        map.put("biome", level.getBiome(player.blockPosition()).unwrapKey()
                .map(key -> key.location().toString()).orElse("unknown"));
        map.put("player_count", level.getServer().getPlayerList().getPlayerCount());
        return map;
    }

    private Map<String, Object> playerList() {
        CompanionPlayer self = requirePlayer();
        MinecraftServer server = self.server;
        List<Map<String, Object>> players = new ArrayList<>();
        for (ServerPlayer listed : server.getPlayerList().getPlayers()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", listed.getGameProfile().getName());
            entry.put("uuid", listed.getUUID().toString());
            // 1.21.1 no longer tracks per-player latency server side.
            entry.put("latency", -1);
            entry.put("game_mode", listed.gameMode.getGameModeForPlayer().getName());
            entry.put("is_companion", listed == self);
            entry.put("x", round(listed.getX()));
            entry.put("y", round(listed.getY()));
            entry.put("z", round(listed.getZ()));
            players.add(entry);
        }
        return Map.of("players", players);
    }

    private Map<String, Object> inventory() {
        CompanionPlayer player = requirePlayer();
        List<Map<String, Object>> stacks = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().items.size(); slot++) {
            ItemStack stack = player.getInventory().items.get(slot);
            if (!stack.isEmpty()) {
                stacks.add(stackInfo(slot, stack));
            }
        }
        return Map.of("items", stacks, "selected_slot", player.getInventory().selected);
    }

    private Map<String, Object> hotbar() {
        CompanionPlayer player = requirePlayer();
        List<Map<String, Object>> slots = new ArrayList<>();
        for (int slot = 0; slot < 9; slot++) {
            slots.add(stackInfo(slot, player.getInventory().items.get(slot)));
        }
        return Map.of("slots", slots, "selected_slot", player.getInventory().selected);
    }

    private Map<String, Object> equipment() {
        CompanionPlayer player = requirePlayer();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mainhand", stackInfo(-1, player.getItemBySlot(EquipmentSlot.MAINHAND)));
        map.put("offhand", stackInfo(-1, player.getItemBySlot(EquipmentSlot.OFFHAND)));
        map.put("head", stackInfo(-1, player.getItemBySlot(EquipmentSlot.HEAD)));
        map.put("chest", stackInfo(-1, player.getItemBySlot(EquipmentSlot.CHEST)));
        map.put("legs", stackInfo(-1, player.getItemBySlot(EquipmentSlot.LEGS)));
        map.put("feet", stackInfo(-1, player.getItemBySlot(EquipmentSlot.FEET)));
        return map;
    }

    private Map<String, Object> nearbyEntities(Map<String, Object> params) {
        CompanionPlayer player = requirePlayer();
        double radius = clamp(num(params, "radius", 16.0D), 1.0D, 64.0D);
        int limit = (int) clamp(num(params, "limit", 32.0D), 1.0D, 128.0D);
        AABB box = player.getBoundingBox().inflate(radius);
        List<Entity> entities = player.serverLevel().getEntities(player, box, e -> e != player);
        entities.sort((a, b) -> Double.compare(a.distanceToSqr(player), b.distanceToSqr(player)));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Entity entity : entities) {
            if (result.size() >= limit) {
                break;
            }
            result.add(entityInfo(entity, player));
        }
        return Map.of("entities", result, "count", result.size());
    }

    /** Durability of every damageable item carried or worn. Read-only. */
    private Map<String, Object> durabilityReport(Map<String, Object> params) {
        CompanionPlayer player = requirePlayer();
        List<Map<String, Object>> items = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            addDurability(items, stack, slot, slot == player.getInventory().selected);
        }
        for (EquipmentSlot slot : new EquipmentSlot[] {
                EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            addDurability(items, player.getItemBySlot(slot), -1, false);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("count", items.size());
        return out;
    }

    private static void addDurability(List<Map<String, Object>> items, ItemStack stack,
                                      int slot, boolean selected) {
        if (stack.isEmpty() || !stack.isDamageableItem()) {
            return;
        }
        int max = stack.getMaxDamage();
        int left = Math.max(0, max - stack.getDamageValue());
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("slot", slot);
        entry.put("item", shortName(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()));
        entry.put("left", left);
        entry.put("max", max);
        entry.put("percent", round1(max <= 0 ? 0.0D : left * 100.0D / max));
        entry.put("low", max > 0 && left <= max * 0.15D);
        entry.put("selected", selected);
        items.add(entry);
    }

    /** Hostiles near the companion and the nearest human, with a recommendation. */
    private Map<String, Object> threatAssessment(Map<String, Object> params) {
        CompanionPlayer player = requirePlayer();
        double radius = clamp(num(params, "radius", 24.0D), 4.0D, 64.0D);
        ServerPlayer human = nearestHuman(player, radius);
        AABB box = player.getBoundingBox().inflate(radius);
        List<Map<String, Object>> threats = new ArrayList<>();
        int fighting = 0;
        int retreating = 0;
        for (Entity entity : player.serverLevel().getEntities(player, box,
                e -> e instanceof net.minecraft.world.entity.monster.Monster && e.isAlive())) {
            double distance = Math.sqrt(entity.distanceToSqr(player));
            boolean creeper = entity instanceof net.minecraft.world.entity.monster.Creeper;
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("type", shortName(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()));
            info.put("distance", round1(distance));
            info.put("creeper", creeper);
            if (entity instanceof LivingEntity living) {
                info.put("health", round1(living.getHealth()));
            }
            info.put("to_human", human == null ? null : round1(Math.sqrt(entity.distanceToSqr(human))));
            threats.add(info);
            if (creeper) {
                if (distance <= 8.0D) {
                    retreating++;
                }
            } else if (distance <= 12.0D) {
                fighting++;
            }
        }
        threats.sort((a, b) -> Double.compare(
                ((Number) a.get("distance")).doubleValue(),
                ((Number) b.get("distance")).doubleValue()));
        String recommendation;
        if (player.getHealth() <= 6.0F || retreating > 0) {
            recommendation = "retreat";
        } else if (fighting > 0) {
            recommendation = "fight";
        } else if (!threats.isEmpty()) {
            recommendation = "watch";
        } else {
            recommendation = "none";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hp", round1(player.getHealth()));
        out.put("threats", threats);
        out.put("count", threats.size());
        out.put("guard_player", this.defendPlayer);
        out.put("recommendation", recommendation);
        return out;
    }

    private Map<String, Object> nearbyBlocks(Map<String, Object> params) {
        CompanionPlayer player = requirePlayer();
        int radius = (int) clamp(num(params, "radius", 8.0D), 1.0D, 16.0D);
        int limit = (int) clamp(num(params, "limit", 64.0D), 1.0D, 256.0D);
        List<String> filters = stringList(params.get("blocks"));
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        Vec3 eye = player.getEyePosition();
        List<Object[]> result = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-radius, -radius, -radius),
                origin.offset(radius, radius, radius))) {
            // Unloaded chunks have no real state; reading them would return air
            // and silently hide the blocks the caller is looking for.
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (!filters.isEmpty() && filters.stream().noneMatch(id::contains)) {
                continue;
            }
            double distance = Math.sqrt(Vec3.atCenterOf(pos).distanceToSqr(eye));
            result.add(new Object[] {shortName(id), List.of(pos.getX(), pos.getY(), pos.getZ())});
            distances.add(distance);
        }
        // Nearest first, then apply the limit so close blocks are never cut off.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) {
            order.add(i);
        }
        order.sort(java.util.Comparator.comparingDouble(distances::get));
        // Grouped by block id as {"oak_log": [[x,y,z], ...]}, nearest first.
        Map<String, List<Object>> grouped = new LinkedHashMap<>();
        int count = 0;
        for (int i = 0; i < order.size() && count < limit; i++) {
            Object[] entry = result.get(order.get(i));
            grouped.computeIfAbsent((String) entry[0], key -> new ArrayList<>()).add(entry[1]);
            count++;
        }
        return Map.of("blocks", grouped, "count", count, "matched", result.size());
    }

    private Map<String, Object> blockInfo(Map<String, Object> params) {
        CompanionPlayer player = requirePlayer();
        BlockPos pos = new BlockPos(intParam(params, "x"), intParam(params, "y"), intParam(params, "z"));
        ServerLevel level = player.serverLevel();
        if (!level.isLoaded(pos)) {
            throw new IllegalArgumentException("block position is not loaded");
        }
        BlockState state = level.getBlockState(pos);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("x", pos.getX());
        map.put("y", pos.getY());
        map.put("z", pos.getZ());
        map.put("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        map.put("air", state.isAir());
        map.put("hardness", state.getDestroySpeed(level, pos));
        map.put("solid", state.isSolid());
        map.put("fluid", !state.getFluidState().isEmpty());
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) {
            map.put("block_entity", BuiltInRegistries.BLOCK_ENTITY_TYPE
                    .getKey(blockEntity.getType()).toString());
        }
        return map;
    }

    private Map<String, Object> entityInfo(Map<String, Object> params) {
        CompanionPlayer player = requirePlayer();
        String query = str(params, "entity");
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("entity is required");
        }
        AABB box = player.getBoundingBox().inflate(64.0D);
        List<Entity> entities = player.serverLevel().getEntities(player, box, e -> e != player);
        for (Entity entity : entities) {
            boolean match = entity.getUUID().toString().equalsIgnoreCase(query)
                    || entity.getName().getString().equalsIgnoreCase(query)
                    || entity.getDisplayName().getString().equalsIgnoreCase(query);
            if (match) {
                return entityInfo(entity, player);
            }
        }
        throw new IllegalArgumentException("entity not found within 64 blocks: " + query);
    }

    private Map<String, Object> targetedObject() {
        CompanionPlayer player = requirePlayer();
        HitResult hit = player.pick(20.0D, 0.0F, false);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", hit.getType().name().toLowerCase(Locale.ROOT));
        if (hit instanceof BlockHitResult blockHit) {
            BlockPos pos = blockHit.getBlockPos();
            BlockState state = player.serverLevel().getBlockState(pos);
            map.put("x", pos.getX());
            map.put("y", pos.getY());
            map.put("z", pos.getZ());
            map.put("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            map.put("face", blockHit.getDirection().getName());
        } else if (hit instanceof EntityHitResult entityHit) {
            map.putAll(entityInfo(entityHit.getEntity(), player));
        }
        return map;
    }

    /**
     * Chat and system messages seen by the integrated server. Supports
     * incremental reads with {@code since}: pass the {@code latest_sequence}
     * returned by the previous call.
     */
    private Map<String, Object> chatHistory(Map<String, Object> params) {
        int limit = (int) clamp(num(params, "limit", 20.0D), 1.0D, 256.0D);
        double sinceValue = num(params, "since", 0.0D);
        long since = sinceValue <= 0 ? 0L : (long) sinceValue;

        List<ChatLog.Entry> entries = since > 0
                ? this.chatLog.since(since, limit)
                : this.chatLog.recent(limit);

        List<Map<String, Object>> messages = new ArrayList<>(entries.size());
        for (ChatLog.Entry entry : entries) {
            messages.add(entry.toMap());
        }

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("messages", messages);
        map.put("count", messages.size());
        map.put("latest_sequence", this.chatLog.latestSequence());
        this.chatPushed = Math.max(this.chatPushed, this.chatLog.latestSequence());
        return map;
    }

    private Map<String, Object> recentEvents(Map<String, Object> params) {
        int limit = (int) clamp(num(params, "limit", 32.0D), 1.0D, 256.0D);
        double sinceValue = num(params, "since", 0.0D);
        long since = sinceValue <= 0 ? 0L : (long) sinceValue;

        List<Event> source = since > 0
                ? this.events.since(since, limit)
                : this.events.recent(limit);

        List<Map<String, Object>> events = new ArrayList<>(source.size());
        for (Event event : source) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sequence", event.sequence());
            item.put("timestamp", event.timestamp());
            item.put("type", event.type());
            item.put("data", event.payload());
            events.add(item);
        }

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("events", events);
        map.put("count", events.size());
        map.put("latest_sequence", this.events.latestSequence());
        return map;
    }

    // ------------------------------------------------------------------
    // Control
    // ------------------------------------------------------------------

    private Map<String, Object> move(Map<String, Object> params) {
        CompanionPlayer player = requireControl();
        double forward = clamp(num(params, "forward", 0.0D), -1.0D, 1.0D);
        double strafe = clamp(num(params, "strafe", 0.0D), -1.0D, 1.0D);
        boolean sprint = bool(params, "sprint", false);
        int ticks = (int) clamp(num(params, "ticks", 20.0D), 1.0D, 200.0D);
        String id = newAction("move", "running");
        startTask(new MoveTask(id, forward, strafe, sprint, ticks), player);
        return actionResult(id, "running");
    }

    private Map<String, Object> look(Map<String, Object> params) {
        requireControl();
        float yaw = (float) num(params, "yaw", 0.0D);
        float pitch = (float) clamp(num(params, "pitch", 0.0D), -90.0D, 90.0D);
        this.companion.controller().look(yaw, pitch);
        return Map.of("ok", true);
    }

    private Map<String, Object> lookAt(Map<String, Object> params) {
        CompanionPlayer player = requireControl();
        double x = num(params, "x", 0.0D);
        double y = num(params, "y", 0.0D);
        double z = num(params, "z", 0.0D);
        double dx = x - player.getX();
        double dy = y - (player.getY() + player.getEyeHeight());
        double dz = z - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        this.companion.controller().look(yaw, (float) clamp(pitch, -90.0D, 90.0D));
        return Map.of("ok", true);
    }

    private Map<String, Object> jump(Map<String, Object> params) {
        requireControl();
        this.companion.controller().jump();
        return Map.of("ok", true);
    }

    private Map<String, Object> setSneak(Map<String, Object> params) {
        requireControl();
        boolean enabled = bool(params, "enabled", false);
        this.sneakHeld = enabled;
        this.companion.controller().setSneak(enabled);
        return Map.of("ok", true, "sneaking", enabled);
    }

    private Map<String, Object> stop() {
        cancelActive("stopped");
        this.companion.controller().stop();
        return Map.of("ok", true);
    }

    private Map<String, Object> gotoBlock(Map<String, Object> params) {
        CompanionPlayer player = requireControl();
        double x = num(params, "x", player.getX());
        // NaN y = "any height": the pathfinder accepts any floor at that x/z.
        double y = num(params, "y", Double.NaN);
        double z = num(params, "z", player.getZ());
        double tolerance = clamp(num(params, "tolerance", DEFAULT_TOLERANCE), 0.5D, 8.0D);
        String id = newAction("goto", "running");
        startTask(new GotoTask(id, x, y, z, tolerance), player);
        armInterrupts(params, player);
        return actionResult(id, "running");
    }

    private Map<String, Object> follow(Map<String, Object> params) {
        CompanionPlayer player = requireControl();
        String name = str(params, "player");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("player is required");
        }
        double distance = clamp(num(params, "distance", 3.0D), 1.0D, 16.0D);
        // Persist the order: a chat line, a finished task or combat must not
        // silently drop the follow. Only "para"/"quedate" clears it.
        this.followTarget = name;
        this.followDistance = distance;
        this.followResume = false;
        String id = newAction("follow", "running");
        startTask(new FollowTask(id, name, distance), player);
        return actionResult(id, "running");
    }

    private Map<String, Object> sendChat(Map<String, Object> params) {
        CompanionPlayer player = requirePlayer();
        String message = str(params, "message");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        MinecraftServer server = player.server;
        String name = player.getGameProfile().getName();
        if (message.startsWith("/")) {
            server.getCommands().performPrefixedCommand(
                    player.createCommandSourceStack(), message.substring(1));
            return Map.of("ok", true, "command", true);
        }
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("<" + name + "> " + message), false);
        // broadcastSystemMessage does not fire ServerChatEvent, so record our
        // own line explicitly to keep get_chat_history complete.
        this.chatLog.add(name, message, "chat", true);
        this.events.add("chat", Map.of("sender", name, "message", message));
        return Map.of("ok", true, "command", false);
    }

    // ------------------------------------------------------------------
    // Item / combat / building
    // ------------------------------------------------------------------

    /**
     * Right-clicks with the item in hand, optionally aimed at a specific block
     * face. Uses the vanilla interaction manager so buckets, bows, food,
     * spawn eggs, doors and block items behave exactly as for a real player.
     */
    private Map<String, Object> useItem(Map<String, Object> params) {
        CompanionPlayer player = requireControl();
        String handName = str(params, "hand");
        InteractionHand hand = ("off".equalsIgnoreCase(handName)
                || "offhand".equalsIgnoreCase(handName))
                ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("the " + handName(hand) + " hand is empty");
        }

        ServerLevel level = player.serverLevel();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hand", handName(hand));
        out.put("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        out.put("count_before", stack.getCount());

        InteractionResult result;
        if (params.containsKey("x") && params.containsKey("y") && params.containsKey("z")) {
            BlockPos pos = new BlockPos(intParam(params, "x"), intParam(params, "y"), intParam(params, "z"));
            if (!level.isLoaded(pos)) {
                throw new IllegalArgumentException("target block is not loaded");
            }
            Direction face = direction(params, Direction.UP);
            Vec3 point = facePoint(pos, face);
            requireBlockReach(player, point);
            lookAtPoint(player, point);
            BlockHitResult hit = new BlockHitResult(point, face, pos, false);
            result = player.gameMode.useItemOn(player, level, stack, hand, hit);
            out.put("target", Map.of(
                    "x", pos.getX(), "y", pos.getY(), "z", pos.getZ(),
                    "face", face.getName()));
        } else {
            result = player.gameMode.useItem(player, level, stack, hand);
        }

        player.swing(hand, true);
        player.containerMenu.broadcastChanges();
        out.put("ok", result.consumesAction());
        out.put("result", result.name().toLowerCase(Locale.ROOT));
        out.put("count_after", player.getItemInHand(hand).getCount());
        return out;
    }

    /**
     * Walks to the nearest bed and lies down. The action stays running while the
     * companion sleeps, so the surrounding night skip happens before it ends.
     */
    private Map<String, Object> sleep(Map<String, Object> params) {
        CompanionPlayer player = requireControl();
        double radius = clamp(num(params, "radius", 32.0D), 4.0D, 96.0D);
        String id = newAction("sleep", "running");
        startTask(new SleepTask(id, radius), player);
        armInterrupts(params, player);
        return actionResult(id, "running");
    }

    /** Melee attack against a nearby entity, with a fully charged swing. */
    private Map<String, Object> attack(Map<String, Object> params) {
        CompanionPlayer player = requireControl();
        String query = str(params, "entity");
        Entity target = findEntity(player, query, 8.0D);

        double range = player.entityInteractionRange() + 0.5D;
        double distance = Math.sqrt(player.distanceToSqr(target));
        if (distance > range) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "target is %.2f blocks away; walk closer (max %.2f)", distance, range));
        }

        lookAtPoint(player, target.getEyePosition());
        float healthBefore = target instanceof LivingEntity living ? living.getHealth() : -1.0F;
        player.attackWithFullStrength(target);
        player.swing(InteractionHand.MAIN_HAND, true);
        player.containerMenu.broadcastChanges();
        float healthAfter = target instanceof LivingEntity living ? living.getHealth() : -1.0F;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("target", target.getName().getString());
        out.put("uuid", target.getUUID().toString());
        out.put("type", BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString());
        out.put("distance", round(distance));
        if (healthBefore >= 0) {
            out.put("health_before", round(healthBefore));
            out.put("health_after", round(healthAfter));
            out.put("damage", round(Math.max(0.0F, healthBefore - healthAfter)));
            out.put("killed", !((LivingEntity) target).isAlive());
        }
        return out;
    }

    /** Eats a food item, applying nutrition, saturation and food effects. */
    private Map<String, Object> eat(Map<String, Object> params) {
        CompanionPlayer player = requirePlayer();
        Inventory inventory = player.getInventory();
        int slot = params.containsKey("slot") ? intParam(params, "slot") : findFoodSlot(player);
        if (slot < 0 || slot >= inventory.getContainerSize()) {
            throw new IllegalArgumentException("no food found in the inventory");
        }

        ItemStack stack = inventory.getItem(slot);
        FoodProperties food = stack.getFoodProperties(player);
        if (food == null) {
            throw new IllegalArgumentException("item in slot " + slot + " is not edible: "
                    + BuiltInRegistries.ITEM.getKey(stack.getItem()));
        }
        if (!player.canEat(false)) {
            throw new IllegalArgumentException("not hungry (food level is full)");
        }

        int foodBefore = player.getFoodData().getFoodLevel();
        float saturationBefore = player.getFoodData().getSaturationLevel();
        int countBefore = stack.getCount();
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();

        ItemStack resultStack = player.eat(player.serverLevel(), stack);
        // Vanilla returns the remaining stack; shrink only if it did not.
        if (!player.getAbilities().instabuild
                && resultStack.getItem() == stack.getItem()
                && resultStack.getCount() == countBefore) {
            resultStack.shrink(1);
        }
        inventory.setItem(slot, resultStack);
        inventory.setChanged();
        player.containerMenu.broadcastChanges();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("item", itemId);
        out.put("slot", slot);
        out.put("food_before", foodBefore);
        out.put("food_after", player.getFoodData().getFoodLevel());
        out.put("saturation_before", round(saturationBefore));
        out.put("saturation_after", round(player.getFoodData().getSaturationLevel()));
        out.put("health", round(player.getHealth()));
        return out;
    }

    /**
     * Places one block from the inventory at an exact coordinate. Picks a solid
     * neighbour, aims at its face and uses the vanilla placement logic, then
     * reads the block back so the caller knows what actually happened.
     */
    private Map<String, Object> placeBlock(Map<String, Object> params) {
        CompanionPlayer player = requireControl();
        ServerLevel level = player.serverLevel();
        BlockPos pos = new BlockPos(intParam(params, "x"), intParam(params, "y"), intParam(params, "z"));
        if (!level.isLoaded(pos)) {
            throw new IllegalArgumentException("target position is not loaded");
        }

        boolean replace = bool(params, "replace", false);
        BlockState existing = level.getBlockState(pos);
        if (!existing.canBeReplaced() && !replace) {
            throw new IllegalArgumentException("position is occupied by "
                    + BuiltInRegistries.BLOCK.getKey(existing.getBlock())
                    + " (pass replace=true to overwrite)");
        }

        String wanted = str(params, "block");
        Inventory inventory = player.getInventory();
        int found = findBlockItemSlot(player, wanted);
        if (found < 0) {
            throw new IllegalArgumentException(wanted == null
                    ? "there is no block item in the inventory"
                    : "no block item matching '" + wanted + "' in the inventory");
        }
        ItemStack candidateStack = inventory.getItem(found);
        if (!(candidateStack.getItem() instanceof BlockItem candidateBlock)) {
            throw new IllegalArgumentException("the selected stack is not a block item");
        }
        String blockId = BuiltInRegistries.BLOCK.getKey(candidateBlock.getBlock()).toString();

        BlockPos support = null;
        Direction supportFace = null;
        for (Direction side : List.of(Direction.DOWN, Direction.NORTH, Direction.SOUTH,
                Direction.WEST, Direction.EAST, Direction.UP)) {
            BlockPos candidate = pos.relative(side);
            if (!level.isLoaded(candidate)) {
                continue;
            }
            BlockState state = level.getBlockState(candidate);
            if (!state.isAir() && state.isSolid()) {
                support = candidate;
                supportFace = side.getOpposite();
                break;
            }
        }
        if (support == null) {
            throw new IllegalArgumentException("no solid neighbour to place against at "
                    + pos.toShortString());
        }

        Vec3 point = facePoint(support, supportFace);
        // Validate reach before touching the inventory, so a failed placement
        // does not silently change the selected hotbar slot.
        requireBlockReach(player, point);

        int hotbarSlot;
        if (found < 9) {
            hotbarSlot = found;
        } else {
            hotbarSlot = inventory.selected;
            ItemStack previous = inventory.getItem(hotbarSlot);
            inventory.setItem(hotbarSlot, inventory.getItem(found));
            inventory.setItem(found, previous);
        }
        inventory.selected = hotbarSlot;

        ItemStack stack = inventory.getItem(hotbarSlot);
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            throw new IllegalArgumentException("the selected stack is not a block item");
        }

        lookAtPoint(player, point);
        BlockHitResult hit = new BlockHitResult(point, supportFace, support, false);
        InteractionResult result = player.gameMode.useItemOn(
                player, level, stack, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND, true);
        player.containerMenu.broadcastChanges();

        BlockState after = level.getBlockState(pos);
        boolean placed = after.getBlock() == blockItem.getBlock();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", placed);
        out.put("requested_block", blockId);
        out.put("placed_at", Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ()));
        out.put("actual_block", BuiltInRegistries.BLOCK.getKey(after.getBlock()).toString());
        out.put("support", Map.of(
                "x", support.getX(), "y", support.getY(), "z", support.getZ(),
                "face", supportFace.getName()));
        out.put("slot", hotbarSlot);
        out.put("remaining", inventory.getItem(hotbarSlot).getCount());
        out.put("result", result.name().toLowerCase(Locale.ROOT));
        if (!placed) {
            out.put("reason", "vanilla placement was rejected (support, collision, protection or rules)");
        }
        return out;
    }

    /**
     * Captures a real screenshot from the companion's eyes. Only works on the
     * client (integrated server), where the renderer lives.
     */
    private Map<String, Object> takeScreenshot(Map<String, Object> params) {
        CompanionPlayer player = requirePlayer();
        if (FMLEnvironment.dist != Dist.CLIENT) {
            throw new IllegalArgumentException("screenshots are only available in the client");
        }
        long timeout = (long) clamp(num(params, "timeout_ms", 6000.0D), 1000.0D, 15000.0D);
        return com.covelliteai.client.PovCapture.capture(
                player.getUUID(),
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(), timeout);
    }

    /**
     * Breaks a single block with the vanilla interaction manager, so drops,
     * tool durability and protection rules behave exactly as for a real player.
     */
    private Map<String, Object> breakBlock(Map<String, Object> params) {
        CompanionPlayer player = requireControl();
        ServerLevel level = player.serverLevel();
        BlockPos pos = new BlockPos(intParam(params, "x"), intParam(params, "y"), intParam(params, "z"));
        if (!level.isLoaded(pos)) {
            throw new IllegalArgumentException("target block is not loaded");
        }
        BlockState before = level.getBlockState(pos);
        if (before.isAir()) {
            throw new IllegalArgumentException("there is no block to break at " + pos.toShortString());
        }
        Vec3 center = Vec3.atCenterOf(pos);
        requireBlockReach(player, center);
        lookAtPoint(player, center);
        String beforeId = BuiltInRegistries.BLOCK.getKey(before.getBlock()).toString();
        player.swing(InteractionHand.MAIN_HAND, true);
        boolean destroyed = player.gameMode.destroyBlock(pos);
        player.containerMenu.broadcastChanges();
        BlockState after = level.getBlockState(pos);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", destroyed);
        out.put("block_before", beforeId);
        out.put("x", pos.getX());
        out.put("y", pos.getY());
        out.put("z", pos.getZ());
        out.put("actual_block", BuiltInRegistries.BLOCK.getKey(after.getBlock()).toString());
        if (!destroyed) {
            out.put("reason", "vanilla rejected the break (protection, game rules or an event)");
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Gathering: walk, mine with the real break time, collect the drops
    // ------------------------------------------------------------------

    /**
     * Starts a resource-gathering action ({@code gather} / {@code mine}). The
     * companion walks to the nearest matching block, picks the best tool from
     * its inventory, breaks blocks with the vanilla break time (no instant
     * mining), digs through obstacles that are safe to remove and picks up the
     * drops. Returns an action id; the bridge waits for the final result.
     */
    private Map<String, Object> gatherWith(Map<String, Object> params, String defaultWhat) {
        CompanionPlayer player = requireControl();
        String what = str(params, "what");
        if (what == null || what.isBlank()) {
            what = defaultWhat;
        }
        int count = (int) clamp(num(params, "count", 8.0D), 1.0D, 256.0D);
        double radius = clamp(num(params, "radius", 24.0D), 4.0D, 32.0D);
        int timeout = (int) clamp(num(params, "timeout_ticks", 3600.0D), 100.0D, 12000.0D);
        boolean allowPlayerBlocks = bool(params, "allow_player_blocks", false);
        String id = newAction("gather", "running");
        startTask(new GatherTask(id, what, count, radius, timeout, false, allowPlayerBlocks, null), player);
        armInterrupts(params, player);
        return actionResult(id, "running");
    }

    /**
     * Chops one tree for real: walks to it and breaks its logs one by one with
     * the vanilla break time, then collects the drops. Only natural trees are
     * touched (logs next to non-persistent leaves) unless
     * {@code allow_player_blocks} is set, so the player's log builds are safe.
     */
    private Map<String, Object> chopTree(Map<String, Object> params) {
        CompanionPlayer player = requireControl();
        ServerLevel level = player.serverLevel();
        int maxLogs = (int) clamp(num(params, "max_logs", 64.0D), 1.0D, 128.0D);
        double radius = clamp(num(params, "radius", 16.0D), 2.0D, 32.0D);

        BlockPos focus = null;
        if (params.containsKey("x") && params.containsKey("y") && params.containsKey("z")) {
            focus = new BlockPos(intParam(params, "x"), intParam(params, "y"), intParam(params, "z"));
            if (!level.isLoaded(focus) || !isLog(level.getBlockState(focus))) {
                throw new IllegalArgumentException("there is no log at " + focus.toShortString());
            }
        } else if (findNearestLog(player, level, radius) == null) {
            throw new IllegalArgumentException("no logs found within " + (int) radius + " blocks");
        }
        String id = newAction("chop_tree", "running");
        startTask(new GatherTask(id, "wood", maxLogs, Math.max(radius, 4.0D), 2400, true,
                bool(params, "allow_player_blocks", false), focus), player);
        armInterrupts(params, player);
        return actionResult(id, "running");
    }

    /** Maps friendly names (wood, hierro, diamond...) to a block predicate. */
    private static Predicate<BlockState> blockMatcher(String what) {
        String key = normalizeCommand(what == null || what.isBlank() ? "wood" : what).trim();
        return switch (key) {
            case "wood", "madera", "log", "logs", "tronco", "troncos", "arbol", "tree", "trees" ->
                    BridgeApi::isLog;
            case "hierro", "iron" -> endsWithBlock("iron_ore");
            case "carbon", "coal" -> endsWithBlock("coal_ore");
            case "oro", "gold" -> endsWithBlock("gold_ore");
            case "diamante", "diamond" -> endsWithBlock("diamond_ore");
            case "cobre", "copper" -> endsWithBlock("copper_ore");
            case "redstone" -> endsWithBlock("redstone_ore");
            case "lapiz", "lapislazuli", "lapis" -> endsWithBlock("lapis_ore");
            case "esmeralda", "emerald" -> endsWithBlock("emerald_ore");
            case "piedra", "stone" -> exactBlock("stone");
            case "cobblestone", "adoquin" -> exactBlock("cobblestone");
            case "tierra", "dirt" -> exactBlock("dirt");
            case "arena", "sand" -> exactBlock("sand");
            case "grava", "gravel" -> exactBlock("gravel");
            default -> state -> {
                ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                return !state.isAir() && (id.toString().equals(key) || id.getPath().contains(key));
            };
        };
    }

    private static Predicate<BlockState> endsWithBlock(String suffix) {
        return state -> !state.isAir()
                && BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().endsWith(suffix);
    }

    private static Predicate<BlockState> exactBlock(String path) {
        return state -> BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().equals(path);
    }

    private static boolean touchesLava(ServerLevel level, BlockPos pos) {
        for (Direction side : Direction.values()) {
            if (level.getFluidState(pos.relative(side)).is(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the companion may break this block just to get through (a wall of
     * dirt, stone, leaves, sand...). Chests, doors, planks, bricks and other
     * things players build with are never cleared.
     */
    private static boolean diggable(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir() || state.getDestroySpeed(level, pos) < 0.0F) {
            return false;
        }
        if (level.getBlockEntity(pos) != null) {
            return false;
        }
        if (state.canBeReplaced()) {
            return true;
        }
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER)
                || state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.LEAVES)
                || path.equals("gravel") || path.equals("clay") || path.equals("snow_block")
                || path.endsWith("_ore");
    }

    private static Map<String, Integer> inventoryTotals(ServerPlayer player) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty()) {
                totals.merge(shortName(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()),
                        stack.getCount(), Integer::sum);
            }
        }
        return totals;
    }

    /** Logs connected (including diagonals) to {@code base}, capped at 200. */
    private static Set<BlockPos> logCluster(ServerLevel level, BlockPos base) {
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        BlockPos start = base.immutable();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty() && seen.size() < 200) {
            BlockPos current = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos next = current.offset(dx, dy, dz);
                        if (seen.contains(next) || !level.isLoaded(next)
                                || !isLog(level.getBlockState(next))) {
                            continue;
                        }
                        seen.add(next);
                        queue.add(next);
                    }
                }
            }
        }
        return seen;
    }

    /** A tree grown by the world generator has leaves that are not player-placed (non persistent). */
    private static boolean naturalTree(ServerLevel level, Set<BlockPos> cluster) {
        for (BlockPos log : cluster) {
            for (Direction side : Direction.values()) {
                BlockState near = level.getBlockState(log.relative(side));
                if (near.getBlock() instanceof LeavesBlock && !near.getValue(LeavesBlock.PERSISTENT)) {
                    return true;
                }
                if (near.is(Blocks.NETHER_WART_BLOCK) || near.is(Blocks.WARPED_WART_BLOCK)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Correct tool for drops counts far more than raw speed. */
    private static float toolScore(ItemStack stack, BlockState state) {
        boolean usable = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
        return (usable ? 1000.0F : 0.0F) + stack.getDestroySpeed(state);
    }

    private static boolean hasUsableTool(ServerPlayer player, BlockState state) {
        if (!state.requiresCorrectToolForDrops()) {
            return true;
        }
        for (int i = 0; i < 36; i++) {
            if (player.getInventory().getItem(i).isCorrectToolForDrops(state)) {
                return true;
            }
        }
        return false;
    }

    /**
     * One gathering job: pick a target, walk to it, break it with the real
     * break time, clear safe obstacles, collect drops, repeat until the goal is
     * met or nothing reachable is left.
     */
    private final class GatherTask implements Task {
        private final String id;
        private final String what;
        private final Predicate<BlockState> matcher;
        private final int goal;
        private final double radius;
        private final int timeoutTicks;
        private final boolean treeMode;
        private final boolean allowPlayerBlocks;
        private final BlockPos focus;

        private final Set<BlockPos> rejected = new HashSet<>();
        private final Set<BlockPos> approvedTrees = new HashSet<>();
        private final Set<BlockPos> rejectedTrees = new HashSet<>();
        private final Set<Integer> ignoredItems = new HashSet<>();
        private final ProgressWatch watch = new ProgressWatch();

        private Set<BlockPos> treeCluster;
        private Map<String, Integer> before;
        private BlockPos target;
        private BlockPos mining;
        private float progress;
        private int lastStage = -1;
        private int swings;
        private int ticks;
        private int broken;
        private int cleared;
        private int unreachable;
        private boolean noTargets;
        private String lastReason;
        private ItemEntity collecting;
        private int collectTicks;
        private PathWalker walker;
        private BlockPos walkerTarget;
        private int pathFailures;
        private int replans;

        GatherTask(String id, String what, int goal, double radius, int timeoutTicks,
                   boolean treeMode, boolean allowPlayerBlocks, BlockPos focus) {
            this.id = id;
            this.what = treeMode ? "wood" : what;
            this.matcher = blockMatcher(this.what);
            this.goal = goal;
            this.radius = radius;
            this.timeoutTicks = timeoutTicks;
            this.treeMode = treeMode;
            this.allowPlayerBlocks = allowPlayerBlocks;
            this.focus = focus;
        }

        @Override
        public boolean tick(CompanionPlayer player, MinecraftServer server) {
            ServerLevel level = player.serverLevel();
            if (this.before == null) {
                this.before = inventoryTotals(player);
                if (this.treeMode && this.focus != null) {
                    this.treeCluster = logCluster(level, this.focus);
                }
            }
            if (++this.ticks > this.timeoutTicks) {
                throw new IllegalStateException("timeout after " + this.timeoutTicks + " ticks");
            }
            if (player.getHealth() <= 4.0F) {
                throw new IllegalStateException("low_health");
            }
            if (this.mining != null) {
                mine(player, level);
                return false;
            }

            boolean wanting = this.broken < this.goal;
            if (wanting && this.target == null && !this.noTargets && !pickTarget(player, level)) {
                this.noTargets = true;
            }
            if (wanting && this.target != null) {
                BlockState state = level.getBlockState(this.target);
                if (state.isAir() || !this.matcher.test(state)) {
                    // Somebody else (or gravity) already removed it.
                    this.target = null;
                    return false;
                }
            }

            // Keep chopping while the next block is within reach; walk to pick
            // up drops only when there is nothing left to hit from here.
            boolean inReach = wanting && this.target != null && inReach(player, this.target);
            if (!inReach && collect(player, level)) {
                return false;
            }
            if (!wanting) {
                return true;
            }
            if (this.target == null) {
                if (this.treeMode && this.broken > 0) {
                    return true;
                }
                String reason = "no " + this.what + " found within " + (int) this.radius + " blocks";
                if (this.lastReason != null) {
                    reason += " (" + this.lastReason + ")";
                }
                throw new IllegalStateException(reason + "; broken " + this.broken + "/" + this.goal);
            }
            approach(player, level);
            return false;
        }

        @Override
        public Map<String, Object> result(CompanionPlayer player) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("what", this.what);
            out.put("broken", this.broken);
            out.put("goal", this.goal);
            if (this.cleared > 0) {
                out.put("cleared", this.cleared);
            }
            if (this.unreachable > 0) {
                out.put("skipped", this.unreachable);
            }
            if (this.lastReason != null) {
                out.put("issue", this.lastReason);
            }
            if (this.before != null) {
                Map<String, Object> gained = new LinkedHashMap<>();
                for (Map.Entry<String, Integer> entry : inventoryTotals(player).entrySet()) {
                    int delta = entry.getValue() - this.before.getOrDefault(entry.getKey(), 0);
                    if (delta > 0) {
                        gained.put(entry.getKey(), delta);
                    }
                }
                if (!gained.isEmpty()) {
                    out.put("gained", gained);
                }
            }
            return out;
        }

        @Override
        public void cleanup(CompanionPlayer player) {
            if (this.mining != null) {
                player.serverLevel().destroyBlockProgress(player.getId(), this.mining, -1);
                this.mining = null;
            }
        }

        private boolean inReach(CompanionPlayer player, BlockPos pos) {
            return player.getEyePosition().distanceTo(Vec3.atCenterOf(pos))
                    <= player.blockInteractionRange() - 0.4D;
        }

        private void reject(BlockPos pos, String reason) {
            this.rejected.add(pos.immutable());
            this.unreachable++;
            if (reason != null) {
                this.lastReason = reason;
            }
            if (pos.equals(this.target)) {
                this.target = null;
            }
            this.watch.reset(Double.MAX_VALUE);
        }

        /** Nearest matching block that is allowed, safe and doable with the tools at hand. */
        private boolean pickTarget(CompanionPlayer player, ServerLevel level) {
            for (int attempt = 0; attempt < 12; attempt++) {
                BlockPos best = scanBest(player, level);
                if (best == null) {
                    return false;
                }
                if (acceptable(player, level, best)) {
                    this.target = best;
                    this.watch.reset(Double.MAX_VALUE);
                    return true;
                }
                this.rejected.add(best);
                this.unreachable++;
            }
            return false;
        }

        private BlockPos scanBest(CompanionPlayer player, ServerLevel level) {
            BlockPos origin = player.blockPosition();
            int r = (int) Math.ceil(this.radius);
            Vec3 here = player.position();
            BlockPos best = null;
            double bestScore = Double.MAX_VALUE;
            for (BlockPos pos : BlockPos.betweenClosed(
                    origin.offset(-r, -r, -r), origin.offset(r, r, r))) {
                if (!level.isLoaded(pos) || this.rejected.contains(pos) || this.rejectedTrees.contains(pos)) {
                    continue;
                }
                BlockState state = level.getBlockState(pos);
                if (state.isAir() || !this.matcher.test(state)) {
                    continue;
                }
                if (this.treeMode && this.treeCluster != null && !this.treeCluster.contains(pos)) {
                    continue;
                }
                // Lower blocks first, like a person working up a trunk.
                double score = Vec3.atCenterOf(pos).distanceToSqr(here) + (pos.getY() - origin.getY()) * 0.5D;
                if (score < bestScore) {
                    bestScore = score;
                    best = pos.immutable();
                }
            }
            return best;
        }

        private boolean acceptable(CompanionPlayer player, ServerLevel level, BlockPos pos) {
            BlockState state = level.getBlockState(pos);
            if (state.getDestroySpeed(level, pos) < 0.0F) {
                this.lastReason = "unbreakable";
                return false;
            }
            if (touchesLava(level, pos)) {
                this.lastReason = "lava_nearby";
                return false;
            }
            if (!hasUsableTool(player, state)) {
                this.lastReason = "need_better_tool:"
                        + shortName(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                return false;
            }
            if (isLog(state) && !this.approvedTrees.contains(pos)) {
                Set<BlockPos> cluster = logCluster(level, pos);
                boolean natural = this.allowPlayerBlocks || naturalTree(level, cluster);
                if (natural) {
                    this.approvedTrees.addAll(cluster);
                    if (this.treeMode && this.treeCluster == null) {
                        this.treeCluster = cluster;
                    }
                } else {
                    this.rejectedTrees.addAll(cluster);
                    this.lastReason = "player_built_logs";
                    return false;
                }
            }
            return true;
        }

        /** Whether this block may be broken just to reach the target. */
        private boolean canClear(ServerLevel level, BlockPos pos, BlockState state) {
            if (this.rejected.contains(pos) || touchesLava(level, pos)) {
                return false;
            }
            if (diggable(level, pos, state)) {
                return true;
            }
            if (isLog(state)) {
                return this.approvedTrees.contains(pos);
            }
            return this.matcher.test(state) && state.getDestroySpeed(level, pos) >= 0.0F;
        }

        private void approach(CompanionPlayer player, ServerLevel level) {
            Vec3 eye = player.getEyePosition();
            Vec3 center = Vec3.atCenterOf(this.target);
            if (inReach(player, this.target)) {
                BlockHitResult hit = level.clip(new ClipContext(
                        eye, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
                BlockPos hitPos = hit.getType() == HitResult.Type.MISS ? this.target : hit.getBlockPos();
                if (hitPos.equals(this.target)) {
                    beginMining(player, level, this.target);
                    return;
                }
                BlockState blocker = level.getBlockState(hitPos);
                if (canClear(level, hitPos, blocker)) {
                    beginMining(player, level, hitPos);
                } else {
                    reject(this.target, "blocked_by:"
                            + shortName(BuiltInRegistries.BLOCK.getKey(blocker.getBlock()).toString()));
                }
                return;
            }

            if (walkByPath(player, level)) {
                return;
            }

            double dx = center.x - player.getX();
            double dz = center.z - player.getZ();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            if (horizontal <= 0.9D) {
                // Right above or below the target but out of reach vertically.
                BlockPos below = player.blockPosition().below();
                BlockState underfoot = level.getBlockState(below);
                BlockState beyond = level.getBlockState(below.below());
                if (center.y < player.getY() && canClear(level, below, underfoot)
                        && !beyond.isAir() && !touchesLava(level, below.below())) {
                    beginMining(player, level, below);
                } else {
                    reject(this.target, "out_of_reach_vertically");
                }
                return;
            }
            try {
                driveTowards(player, center.x, center.y, center.z, 0.9D, this.watch);
            } catch (IllegalStateException e) {
                reject(this.target, "cannot_reach");
                return;
            }
            if (player.horizontalCollision && this.watch.idleTicks() >= 6) {
                BlockPos front = frontBlocker(player, level, dx, dz);
                if (front != null) {
                    beginMining(player, level, front);
                }
            }
        }

        /**
         * A* walk to a spot from which the target is within reach. Returns false
         * when no usable path exists, so the caller falls back to straight-line
         * driving plus digging through obstacles.
         */
        private boolean walkByPath(CompanionPlayer player, ServerLevel level) {
            if (!this.target.equals(this.walkerTarget)) {
                this.walker = null;
                this.walkerTarget = this.target;
                this.pathFailures = 0;
                this.replans = 0;
            }
            if (this.pathFailures >= 3 || this.replans >= 8) {
                return false;
            }
            if (this.walker == null) {
                Vec3 center = Vec3.atCenterOf(this.target);
                double reach = player.blockInteractionRange() - 0.7D;
                Pathfinder.Result result = Pathfinder.find(level, player.blockPosition(),
                        pos -> new Vec3(pos.getX() + 0.5D, pos.getY() + 1.62D, pos.getZ() + 0.5D)
                                .distanceTo(center) <= reach,
                        center, 4000);
                if (result.path().size() < 2) {
                    this.pathFailures++;
                    return false;
                }
                this.replans++;
                this.walker = new PathWalker(result.path());
            }
            PathStatus status = this.walker.tick(player);
            if (status == PathStatus.STUCK) {
                this.walker = null;
                this.pathFailures++;
            } else if (status == PathStatus.ARRIVED) {
                this.walker = null;
            }
            return true;
        }

        private BlockPos frontBlocker(CompanionPlayer player, ServerLevel level, double dx, double dz) {
            Direction facing = Direction.getNearest(dx, 0.0D, dz);
            BlockPos feet = player.blockPosition().relative(facing);
            for (BlockPos candidate : new BlockPos[] {feet, feet.above()}) {
                BlockState state = level.getBlockState(candidate);
                if (state.isAir() || state.getCollisionShape(level, candidate).isEmpty()) {
                    continue;
                }
                if (canClear(level, candidate, state)) {
                    return candidate;
                }
            }
            return null;
        }

        private void beginMining(CompanionPlayer player, ServerLevel level, BlockPos pos) {
            BlockState state = level.getBlockState(pos);
            if (state.getDestroySpeed(level, pos) < 0.0F) {
                reject(this.target, "unbreakable");
                return;
            }
            BridgeApi.this.companion.controller().stop();
            selectBestTool(player, state);
            // More than ~30 s for one block means the wrong tool: not worth it.
            if (!player.getAbilities().instabuild
                    && state.getDestroyProgress(player, level, pos) < 1.0F / 600.0F) {
                reject(this.target, "too_slow_without_tool:"
                        + shortName(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()));
                return;
            }
            this.mining = pos.immutable();
            this.progress = 0.0F;
            this.lastStage = -1;
            this.swings = 0;
        }

        private void selectBestTool(CompanionPlayer player, BlockState state) {
            Inventory inventory = player.getInventory();
            int best = inventory.selected;
            float bestScore = toolScore(inventory.getItem(best), state);
            for (int i = 0; i < 36; i++) {
                float score = toolScore(inventory.getItem(i), state);
                if (score > bestScore + 0.01F) {
                    best = i;
                    bestScore = score;
                }
            }
            if (best >= 9) {
                // The tool is in the backpack: swap it into the hand slot.
                int slot = inventory.selected;
                ItemStack previous = inventory.getItem(slot);
                inventory.setItem(slot, inventory.getItem(best));
                inventory.setItem(best, previous);
                best = slot;
            }
            inventory.selected = best;
            player.containerMenu.broadcastChanges();
        }

        /** One tick of breaking: accumulates the vanilla destroy progress and swings. */
        private void mine(CompanionPlayer player, ServerLevel level) {
            BlockState state = level.getBlockState(this.mining);
            Vec3 center = Vec3.atCenterOf(this.mining);
            if (state.isAir()
                    || player.getEyePosition().distanceTo(center) > player.blockInteractionRange() + 0.5D) {
                level.destroyBlockProgress(player.getId(), this.mining, -1);
                this.mining = null;
                return;
            }
            lookAtPoint(player, center);
            this.progress += player.getAbilities().instabuild
                    ? 1.0F : state.getDestroyProgress(player, level, this.mining);
            if (this.swings++ % 6 == 0) {
                player.swing(InteractionHand.MAIN_HAND, true);
            }
            if (this.progress < 1.0F) {
                int stage = (int) (this.progress * 10.0F);
                if (stage != this.lastStage) {
                    level.destroyBlockProgress(player.getId(), this.mining, stage);
                    this.lastStage = stage;
                }
                return;
            }

            BlockPos done = this.mining;
            boolean matched = this.matcher.test(state);
            this.mining = null;
            level.destroyBlockProgress(player.getId(), done, -1);
            if (player.gameMode.destroyBlock(done)) {
                player.containerMenu.broadcastChanges();
                if (matched) {
                    this.broken++;
                } else {
                    this.cleared++;
                }
                if (done.equals(this.target)) {
                    this.target = null;
                }
                this.watch.reset(Double.MAX_VALUE);
            } else {
                reject(done, "break_rejected");
            }
        }

        /** Walks to the nearest drop and picks it up. Returns true while it is busy doing so. */
        private boolean collect(CompanionPlayer player, ServerLevel level) {
            if (this.collecting != null
                    && (!this.collecting.isAlive() || this.collectTicks > 80)) {
                if (this.collecting.isAlive()) {
                    this.ignoredItems.add(this.collecting.getId());
                }
                this.collecting = null;
            }
            if (this.collecting == null) {
                ItemEntity nearest = null;
                double best = 36.0D;
                for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class,
                        player.getBoundingBox().inflate(6.0D, 4.0D, 6.0D))) {
                    if (!item.isAlive() || this.ignoredItems.contains(item.getId())) {
                        continue;
                    }
                    double distance = item.distanceToSqr(player);
                    if (distance < best) {
                        best = distance;
                        nearest = item;
                    }
                }
                if (nearest == null) {
                    return false;
                }
                this.collecting = nearest;
                this.collectTicks = 0;
            }

            this.collectTicks++;
            ItemEntity item = this.collecting;
            CompanionController controller = BridgeApi.this.companion.controller();
            if (item.distanceToSqr(player) <= 1.6D * 1.6D) {
                controller.stop();
                if (!item.hasPickUpDelay()) {
                    item.playerTouch(player);
                }
                if (!item.isAlive()) {
                    this.collecting = null;
                }
                return true;
            }
            double dx = item.getX() - player.getX();
            double dz = item.getZ() - player.getZ();
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            controller.look(yaw, 0.0F);
            if (player.horizontalCollision && player.onGround()) {
                controller.jump();
            }
            controller.move(1.0D, 0.0D, false, 3);
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Persistent memory: remember / recall / forget / go
    // ------------------------------------------------------------------

    private WorldMemory memory() {
        MinecraftServer server = requireServer();
        if (this.worldMemory == null || this.memoryServer != server) {
            this.worldMemory = WorldMemory.forWorld(server);
            this.memoryServer = server;
        }
        return this.worldMemory;
    }

    private static String dimName(ServerLevel level) {
        return shortName(level.dimension().location().toString());
    }

    /** Saves a named place (default: where the companion stands now). Same name = update. */
    private Map<String, Object> remember(Map<String, Object> params) {
        CompanionPlayer player = requirePlayer();
        String name = str(params, "name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        double x = params.containsKey("x") ? num(params, "x", player.getX()) : player.getX();
        double y = params.containsKey("y") ? num(params, "y", player.getY()) : player.getY();
        double z = params.containsKey("z") ? num(params, "z", player.getZ()) : player.getZ();
        boolean updated = memory().put(name, x, y, z, dimName(player.serverLevel()), str(params, "note"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("name", name.trim());
        out.put("at", List.of(round1(x), round1(y), round1(z)));
        out.put("updated", updated);
        out.put("total", memory().all().size());
        return out;
    }

    /** One saved place by name, or all of them (nearest first). */
    private Map<String, Object> recall(Map<String, Object> params) {
        CompanionPlayer player = requirePlayer();
        String name = str(params, "name");
        String dim = dimName(player.serverLevel());
        if (name != null && !name.isBlank()) {
            WorldMemory.Place place = memory().find(name);
            if (place == null) {
                throw new IllegalArgumentException("unknown place '" + name + "'; known: " + knownPlaces());
            }
            return placeInfo(place, player, dim);
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (WorldMemory.Place place : memory().all()) {
            list.add(placeInfo(place, player, dim));
        }
        list.sort(java.util.Comparator.comparingDouble(
                m -> m.containsKey("d") ? ((Number) m.get("d")).doubleValue() : Double.MAX_VALUE));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("places", list);
        out.put("count", list.size());
        return out;
    }

    private Map<String, Object> placeInfo(WorldMemory.Place place, CompanionPlayer player, String dim) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", place.name());
        map.put("x", round1(place.x()));
        map.put("y", round1(place.y()));
        map.put("z", round1(place.z()));
        map.put("dim", place.dim());
        if (place.note() != null) {
            map.put("note", place.note());
        }
        if (place.dim().equals(dim)) {
            double dx = place.x() - player.getX();
            double dy = place.y() - player.getY();
            double dz = place.z() - player.getZ();
            map.put("d", round1(Math.sqrt(dx * dx + dy * dy + dz * dz)));
        }
        return map;
    }

    private String knownPlaces() {
        List<String> names = new ArrayList<>();
        for (WorldMemory.Place place : memory().all()) {
            names.add(place.name());
        }
        return names.isEmpty() ? "(none)" : String.join(", ", names);
    }

    private Map<String, Object> forget(Map<String, Object> params) {
        requirePlayer();
        String name = str(params, "name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (!memory().remove(name)) {
            throw new IllegalArgumentException("unknown place '" + name + "'; known: " + knownPlaces());
        }
        return Map.of("ok", true, "total", memory().all().size());
    }

    /** Walks to a saved place (A*, waits, interruptible like goto). */
    private Map<String, Object> goToPlace(Map<String, Object> params) {
        CompanionPlayer player = requireControl();
        String name = str(params, "name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        WorldMemory.Place place = memory().find(name);
        if (place == null) {
            throw new IllegalArgumentException("unknown place '" + name + "'; known: " + knownPlaces());
        }
        if (!place.dim().equals(dimName(player.serverLevel()))) {
            throw new IllegalArgumentException("'" + place.name() + "' is in another dimension (" + place.dim() + ")");
        }
        Map<String, Object> target = new LinkedHashMap<>(params);
        target.remove("name");
        target.put("x", place.x());
        target.put("y", place.y());
        target.put("z", place.z());
        target.putIfAbsent("tolerance", 2.0D);
        Map<String, Object> result = new LinkedHashMap<>(gotoBlock(target));
        result.put("place", place.name());
        return result;
    }

    /** Up to five saved places close by, e.g. "home 12m N". */
    private List<String> nearbyPlaces(CompanionPlayer player) {
        List<Object[]> found = new ArrayList<>();
        String dim = dimName(player.serverLevel());
        for (WorldMemory.Place place : memory().all()) {
            if (!place.dim().equals(dim)) {
                continue;
            }
            double dx = place.x() - player.getX();
            double dy = place.y() - player.getY();
            double dz = place.z() - player.getZ();
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 <= 128.0D * 128.0D) {
                found.add(new Object[] {d2, describe(place.name(), dx, dy, dz)});
            }
        }
        found.sort((a, b) -> Double.compare((Double) a[0], (Double) b[0]));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < found.size() && i < 5; i++) {
            out.add((String) found.get(i)[1]);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Auto-crafting: plan the whole recipe tree, then do every step
    // ------------------------------------------------------------------

    private record PlannedCraft(RecipeHolder<CraftingRecipe> holder, int crafts) {}

    private record PlanSnapshot(Map<Item, Integer> pool, int steps, Map<Item, Integer> missing, boolean needsTable) {}

    private static final class CraftPlan {
        final Map<Item, Integer> pool = new LinkedHashMap<>();
        final List<PlannedCraft> steps = new ArrayList<>();
        final Map<Item, Integer> missing = new LinkedHashMap<>();
        boolean needsTable;
    }

    private Map<Item, List<RecipeHolder<CraftingRecipe>>> recipesByResult(ServerPlayer player) {
        if (this.recipeIndex == null || this.recipeIndexServer != player.server) {
            Map<Item, List<RecipeHolder<CraftingRecipe>>> index = new LinkedHashMap<>();
            for (RecipeHolder<CraftingRecipe> holder : player.server.getRecipeManager()
                    .getAllRecipesFor(RecipeType.CRAFTING)) {
                CraftingRecipe recipe = holder.value();
                if (recipe.getIngredients().stream().allMatch(Ingredient::isEmpty)) {
                    continue;
                }
                ItemStack result = recipe.getResultItem(player.server.registryAccess());
                if (result.isEmpty()) {
                    continue;
                }
                index.computeIfAbsent(result.getItem(), key -> new ArrayList<>()).add(holder);
            }
            for (List<RecipeHolder<CraftingRecipe>> list : index.values()) {
                list.sort(java.util.Comparator.comparingInt(
                        holder -> (int) holder.value().getIngredients().stream().filter(i -> !i.isEmpty()).count()));
            }
            this.recipeIndex = index;
            this.recipeIndexServer = player.server;
        }
        return this.recipeIndex;
    }

    /**
     * Compression/decompression recipes (9 ingots <-> block, ingot -> 9 nuggets).
     * Never used to fill a shortage of an intermediate, only when asked directly.
     */
    private static boolean isStorageRecipe(CraftingRecipe recipe, ItemStack result) {
        List<Ingredient> ingredients = recipe.getIngredients().stream().filter(i -> !i.isEmpty()).toList();
        if (ingredients.size() == 1 && result.getCount() >= 9) {
            return true;
        }
        if (ingredients.size() >= 4) {
            Item first = null;
            for (Ingredient ingredient : ingredients) {
                ItemStack[] options = ingredient.getItems();
                if (options.length != 1) {
                    return false;
                }
                if (first == null) {
                    first = options[0].getItem();
                } else if (first != options[0].getItem()) {
                    return false;
                }
            }
            return first != null;
        }
        return false;
    }

    private CraftPlan newPlan(ServerPlayer player) {
        CraftPlan plan = new CraftPlan();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                plan.pool.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return plan;
    }

    private static PlanSnapshot snapshot(CraftPlan plan) {
        return new PlanSnapshot(new LinkedHashMap<>(plan.pool), plan.steps.size(),
                new LinkedHashMap<>(plan.missing), plan.needsTable);
    }

    private static void restore(CraftPlan plan, PlanSnapshot snapshot) {
        plan.pool.clear();
        plan.pool.putAll(snapshot.pool());
        while (plan.steps.size() > snapshot.steps()) {
            plan.steps.remove(plan.steps.size() - 1);
        }
        plan.missing.clear();
        plan.missing.putAll(snapshot.missing());
        plan.needsTable = snapshot.needsTable();
    }

    private static int totalMissing(CraftPlan plan) {
        int total = 0;
        for (int value : plan.missing.values()) {
            total += value;
        }
        return total;
    }

    /**
     * Makes {@code count} of {@code item} available in the simulated pool:
     * takes what is held, crafts the rest (recursively) and records what cannot
     * be crafted as missing. Returns false when something is still missing.
     */
    private boolean obtain(CraftPlan plan, Item item, int count, Set<Item> visiting, ServerPlayer player) {
        int have = plan.pool.getOrDefault(item, 0);
        int use = Math.min(have, count);
        if (use > 0) {
            plan.pool.put(item, have - use);
            count -= use;
        }
        if (count <= 0) {
            return true;
        }
        boolean top = visiting.isEmpty();
        List<RecipeHolder<CraftingRecipe>> usable = new ArrayList<>();
        for (RecipeHolder<CraftingRecipe> holder : recipesByResult(player).getOrDefault(item, List.of())) {
            ItemStack result = holder.value().getResultItem(player.server.registryAccess());
            if (top || !isStorageRecipe(holder.value(), result)) {
                usable.add(holder);
            }
        }
        if (usable.isEmpty() || !visiting.add(item)) {
            plan.missing.merge(item, count, Integer::sum);
            return false;
        }
        try {
            for (RecipeHolder<CraftingRecipe> holder : usable) {
                PlanSnapshot before = snapshot(plan);
                if (solveRecipe(plan, holder, count, visiting, player)) {
                    return true;
                }
                restore(plan, before);
            }
            // Nothing worked: redo the first recipe without rollback so the
            // real shortages (the raw materials) end up in 'missing'.
            solveRecipe(plan, usable.get(0), count, visiting, player);
            return false;
        } finally {
            visiting.remove(item);
        }
    }

    private boolean solveRecipe(CraftPlan plan, RecipeHolder<CraftingRecipe> holder, int count,
                                Set<Item> visiting, ServerPlayer player) {
        CraftingRecipe recipe = holder.value();
        ItemStack result = recipe.getResultItem(player.server.registryAccess());
        int perCraft = Math.max(1, result.getCount());
        int crafts = (count + perCraft - 1) / perCraft;
        int missingBefore = totalMissing(plan);
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) {
                obtainIngredient(plan, ingredient, crafts, visiting, player);
            }
        }
        if (totalMissing(plan) > missingBefore) {
            return false;
        }
        plan.steps.add(new PlannedCraft(holder, crafts));
        if (!recipe.canCraftInDimensions(2, 2)) {
            plan.needsTable = true;
        }
        int surplus = perCraft * crafts - count;
        if (surplus > 0) {
            plan.pool.merge(result.getItem(), surplus, Integer::sum);
        }
        return true;
    }

    /** Any item of the ingredient works: prefer the one held most, else craft the first feasible option. */
    private void obtainIngredient(CraftPlan plan, Ingredient ingredient, int amount,
                                  Set<Item> visiting, ServerPlayer player) {
        ItemStack[] options = ingredient.getItems();
        if (options.length == 0) {
            return;
        }
        Item best = null;
        int bestCount = 0;
        for (ItemStack option : options) {
            int held = plan.pool.getOrDefault(option.getItem(), 0);
            if (held > bestCount) {
                best = option.getItem();
                bestCount = held;
            }
        }
        List<Item> order = new ArrayList<>();
        if (best != null) {
            order.add(best);
        }
        for (int i = 0; i < options.length && order.size() < 12; i++) {
            if (!order.contains(options[i].getItem())) {
                order.add(options[i].getItem());
            }
        }
        for (Item candidate : order) {
            PlanSnapshot before = snapshot(plan);
            int missingBefore = totalMissing(plan);
            if (obtain(plan, candidate, amount, visiting, player) && totalMissing(plan) == missingBefore) {
                return;
            }
            restore(plan, before);
        }
        plan.missing.merge(options[0].getItem(), amount, Integer::sum);
    }

    /** Plans the target; when it needs a table we do not have, plans a table first. */
    private CraftPlan planCraft(CompanionPlayer player, Item target, int count) {
        CraftPlan plan = newPlan(player);
        obtain(plan, target, count, new HashSet<>(), player);
        if (plan.missing.isEmpty() && plan.needsTable
                && findNearbyCraftingTable(player, 4.0D) == null
                && findBlockItemSlot(player, "crafting_table") < 0) {
            CraftPlan withTable = newPlan(player);
            obtain(withTable, Items.CRAFTING_TABLE, 1, new HashSet<>(), player);
            obtain(withTable, target, count, new HashSet<>(), player);
            return withTable;
        }
        return plan;
    }

    private static String itemId(Item item) {
        return shortName(BuiltInRegistries.ITEM.getKey(item).toString());
    }

    private static Map<String, Object> missingMap(CraftPlan plan) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> entry : plan.missing.entrySet()) {
            out.put(itemId(entry.getKey()), entry.getValue());
        }
        return out;
    }

    private static boolean onlyLogsMissing(CraftPlan plan) {
        for (Item item : plan.missing.keySet()) {
            if (!new ItemStack(item).is(ItemTags.LOGS)) {
                return false;
            }
        }
        return !plan.missing.isEmpty();
    }

    /** Spanish/English phrase -> item id ("pico de hierro" -> iron_pickaxe). */
    private static String resolveItemQuery(String raw) {
        String text = normalizeCommand(raw);
        String material = text.contains("netherite") ? "netherite_"
                : text.contains("hierro") || text.contains("iron") ? "iron_"
                : text.contains("piedra") || text.contains("stone") ? "stone_"
                : text.contains("diamante") || text.contains("diamond") ? "diamond_"
                : text.contains("oro") || text.contains("gold") ? "golden_"
                : text.contains("cuero") || text.contains("leather") ? "leather_"
                : text.contains("madera") || text.contains("wood") ? "wooden_"
                : null;
        String tool = text.contains("pico") || text.contains("pickaxe") ? "pickaxe"
                : text.contains("hacha") || text.matches(".*\\baxe\\b.*") ? "axe"
                : text.contains("espada") || text.contains("sword") ? "sword"
                : text.contains("pala") || text.contains("shovel") ? "shovel"
                : text.contains("azada") || text.contains("hoe") ? "hoe"
                : null;
        if (tool != null) {
            return (material == null ? "wooden_" : material) + tool;
        }
        String armor = text.contains("casco") || text.contains("helmet") ? "helmet"
                : text.contains("pechera") || text.contains("chestplate") ? "chestplate"
                : text.contains("pantalon") || text.contains("leggings") ? "leggings"
                : text.contains("botas") || text.contains("boots") ? "boots"
                : null;
        if (armor != null) {
            return (material == null ? "iron_" : material) + armor;
        }
        if (text.contains("mesa") || text.contains("crafting table") || text.contains("workbench")) return "crafting_table";
        if (text.contains("palo") || text.contains("stick")) return "stick";
        if (text.contains("tabla") || text.contains("plank")) return "planks";
        if (text.contains("antorcha") || text.contains("torch")) return "torch";
        if (text.contains("cofre") || text.contains("chest")) return "chest";
        if (text.contains("horno") || text.contains("furnace")) return "furnace";
        if (text.contains("escudo")) return "shield";
        if (text.contains("balde")) return "bucket";
        if (text.contains("arco")) return "bow";
        if (text.contains("flecha")) return "arrow";
        return text.replace(' ', '_');
    }

    private Item resolveCraftTarget(CompanionPlayer player, String query) {
        String key = resolveItemQuery(query);
        Item partial = null;
        String partialId = null;
        for (Item item : recipesByResult(player).keySet()) {
            String id = itemId(item);
            if (id.equals(key) || BuiltInRegistries.ITEM.getKey(item).toString().equals(key)) {
                return item;
            }
            if (id.contains(key) && (partialId == null || id.length() < partialId.length())) {
                partial = item;
                partialId = id;
            }
        }
        if (partial != null) {
            return partial;
        }
        throw new IllegalArgumentException("no crafting recipe matches '" + query + "'");
    }

    /**
     * Crafts an item end to end: plans the recipe tree (planks, sticks, table,
     * tools...), places a crafting table when a 3x3 recipe needs one and does
     * every step. If only logs are missing it first gathers wood (the call then
     * returns a running action and finishes by itself). Other shortages are
     * reported in {@code missing}.
     */
    private Map<String, Object> craftAuto(Map<String, Object> params) {
        CompanionPlayer player = requireControl();
        String query = str(params, "item");
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("item is required (e.g. 'pico de hierro', 'iron_pickaxe')");
        }
        int count = (int) clamp(num(params, "count", 1.0D), 1.0D, 256.0D);
        Item target = resolveCraftTarget(player, query);
        CraftPlan plan = planCraft(player, target, count);

        if (!plan.missing.isEmpty()) {
            if (bool(params, "gather", true) && onlyLogsMissing(plan)) {
                int logs = Math.max(1, totalMissing(plan));
                String id = newAction("craft", "running");
                GatherTask gather = new GatherTask(id, "wood", logs, 24.0D, 3600, false, false, null);
                startTask(new CraftTask(id, target, count, gather), player);
                armInterrupts(params, player);
                Map<String, Object> out = actionResult(id, "running");
                out.put("note", "gathering " + logs + " log(s) first, then crafting " + itemId(target));
                return out;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("item", itemId(target));
            out.put("missing", missingMap(plan));
            out.put("hint", "get them with gather/mine or container_take; smelting is not automated");
            return out;
        }
        return executeCraft(player, plan, target, count);
    }

    private Map<String, Object> executeCraft(CompanionPlayer player, CraftPlan plan, Item target, int count) {
        Inventory inventory = player.getInventory();
        Map<String, Integer> before = inventoryTotals(player);
        List<String> done = new ArrayList<>();
        BlockPos placedTable = null;
        for (PlannedCraft step : plan.steps) {
            CraftingRecipe recipe = step.holder().value();
            if (!recipe.canCraftInDimensions(2, 2) && findNearbyCraftingTable(player, 4.0D) == null) {
                placedTable = ensureTable(player);
            }
            ItemStack result = recipe.getResultItem(player.server.registryAccess());
            int made = 0;
            for (int i = 0; i < step.crafts(); i++) {
                if (!consumeIngredients(inventory, recipe.getIngredients())) {
                    break;
                }
                made++;
            }
            if (made == 0) {
                throw new IllegalStateException("craft_failed:" + itemId(result.getItem()));
            }
            ItemStack leftover = insertIntoInventory(inventory, result.copyWithCount(result.getCount() * made));
            if (!leftover.isEmpty()) {
                player.drop(leftover, false);
            }
            done.add(itemId(result.getItem()) + " x" + (result.getCount() * made));
        }
        player.containerMenu.broadcastChanges();

        int produced = inventoryTotals(player).getOrDefault(itemId(target), 0)
                - before.getOrDefault(itemId(target), 0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", produced > 0);
        out.put("item", itemId(target));
        out.put("requested", count);
        out.put("produced", Math.max(produced, 0));
        out.put("steps", done);
        if (placedTable != null) {
            out.put("placed_table", positionMap(placedTable));
        }
        return out;
    }

    /** Puts a crafting table from the inventory on a free spot next to the companion. */
    private BlockPos ensureTable(CompanionPlayer player) {
        if (findBlockItemSlot(player, "crafting_table") < 0) {
            throw new IllegalStateException("no_crafting_table");
        }
        ServerLevel level = player.serverLevel();
        BlockPos base = player.blockPosition();
        int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        for (int[] d : around) {
            BlockPos pos = base.offset(d[0], 0, d[1]);
            if (!level.getBlockState(pos).canBeReplaced() || !level.getBlockState(pos.below()).isSolid()) {
                continue;
            }
            try {
                Map<String, Object> placed = placeBlock(Map.of(
                        "x", pos.getX(), "y", pos.getY(), "z", pos.getZ(), "block", "crafting_table"));
                if (Boolean.TRUE.equals(placed.get("ok"))) {
                    return pos;
                }
            } catch (IllegalArgumentException ignored) {
                // Try the next spot.
            }
        }
        throw new IllegalStateException("no_room_for_crafting_table");
    }

    private static String craftReply(Map<String, Object> result, String item) {
        if (Boolean.TRUE.equals(result.get("ok"))) {
            return "listo, crafteado: " + item;
        }
        if ("running".equals(result.get("state"))) {
            return "dale, primero junto madera y despues crafteo " + item;
        }
        if (result.get("missing") instanceof Map<?, ?> missing && !missing.isEmpty()) {
            return "me falta: " + missing;
        }
        return "no pude craftear " + item;
    }

    /** Gathers the missing logs, then plans again and crafts. */
    private final class CraftTask implements Task {
        private final String id;
        private final Item target;
        private final int count;
        private GatherTask gather;
        private Map<String, Object> gathered = Map.of();
        private Map<String, Object> crafted = Map.of();

        CraftTask(String id, Item target, int count, GatherTask gather) {
            this.id = id;
            this.target = target;
            this.count = count;
            this.gather = gather;
        }

        @Override
        public boolean tick(CompanionPlayer player, MinecraftServer server) {
            if (this.gather != null) {
                if (!this.gather.tick(player, server)) {
                    return false;
                }
                this.gathered = this.gather.result(player);
                this.gather.cleanup(player);
                this.gather = null;
            }
            CraftPlan plan = planCraft(player, this.target, this.count);
            if (!plan.missing.isEmpty()) {
                throw new IllegalStateException("still_missing:" + missingMap(plan));
            }
            this.crafted = executeCraft(player, plan, this.target, this.count);
            return true;
        }

        @Override
        public Map<String, Object> result(CompanionPlayer player) {
            Map<String, Object> out = new LinkedHashMap<>(this.crafted);
            if (!this.gathered.isEmpty()) {
                out.put("gathered", this.gathered);
            }
            return out;
        }

        @Override
        public void cleanup(CompanionPlayer player) {
            if (this.gather != null) {
                this.gather.cleanup(player);
            }
        }
    }

    private static final class CraftingPlan {
        final RecipeHolder<CraftingRecipe> holder;
        final int crafts;

        CraftingPlan(RecipeHolder<CraftingRecipe> holder, int crafts) {
            this.holder = holder;
            this.crafts = crafts;
        }
    }

    private static final class ContainerRef {
        final BlockPos pos;
        final Container container;

        ContainerRef(BlockPos pos, Container container) {
            this.pos = pos;
            this.container = container;
        }
    }

    /** Picks the matching recipe that allows the most crafts with the current items. */
    private static CraftingPlan findCraftingRecipe(ServerPlayer player, String wanted) {
        String query = wanted.toLowerCase(Locale.ROOT).trim();
        List<ItemStack> pool = snapshotIngredients(player.getInventory());
        CraftingPlan best = null;
        for (RecipeHolder<CraftingRecipe> holder : player.server.getRecipeManager()
                .getAllRecipesFor(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = holder.value();
            List<Ingredient> ingredients = recipe.getIngredients();
            if (ingredients.stream().allMatch(Ingredient::isEmpty)) {
                continue;
            }
            ItemStack result = recipe.getResultItem(player.server.registryAccess());
            if (result.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
            boolean matches;
            if (query.contains(":")) {
                matches = id.equals(query);
            } else {
                String shortId = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
                matches = id.contains(query) || shortId.equals(query);
            }
            if (!matches) {
                continue;
            }
            int possible = craftsPossible(pool, ingredients);
            if (best == null || possible > best.crafts) {
                best = new CraftingPlan(holder, possible);
            }
        }
        return best;
    }

    private static List<ItemStack> snapshotIngredients(Inventory inventory) {
        List<ItemStack> pool = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                pool.add(stack.copy());
            }
        }
        return pool;
    }

    /**
     * Simulates consuming the recipe ingredients slot by slot and returns how
     * many times the recipe fits in the pooled items.
     */
    private static int craftsPossible(List<ItemStack> pool, List<Ingredient> ingredients) {
        int[] counts = new int[pool.size()];
        for (int i = 0; i < pool.size(); i++) {
            counts[i] = pool.get(i).getCount();
        }
        int crafts = 0;
        while (crafts < 999) {
            boolean ok = true;
            for (Ingredient ingredient : ingredients) {
                if (ingredient.isEmpty()) {
                    continue;
                }
                int slot = -1;
                for (int i = 0; i < pool.size(); i++) {
                    if (counts[i] > 0 && ingredient.test(pool.get(i))) {
                        slot = i;
                        break;
                    }
                }
                if (slot < 0) {
                    ok = false;
                    break;
                }
                counts[slot]--;
            }
            if (!ok) {
                break;
            }
            crafts++;
        }
        return crafts;
    }

    /** Consumes one item per non-empty ingredient from the real inventory. */
    private static boolean consumeIngredients(Inventory inventory, List<Ingredient> ingredients) {
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) {
                continue;
            }
            boolean consumed = false;
            for (int i = 0; i < 36; i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty() && ingredient.test(stack)) {
                    stack.shrink(1);
                    if (stack.isEmpty()) {
                        inventory.setItem(i, ItemStack.EMPTY);
                    }
                    consumed = true;
                    break;
                }
            }
            if (!consumed) {
                return false;
            }
        }
        return true;
    }

    /** Adds a stack to the main inventory and returns whatever did not fit. */
    private static ItemStack insertIntoInventory(Inventory inventory, ItemStack stack) {
        for (int i = 0; i < 36 && !stack.isEmpty(); i++) {
            ItemStack existing = inventory.getItem(i);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, stack)) {
                int room = existing.getMaxStackSize() - existing.getCount();
                int move = Math.min(room, stack.getCount());
                if (move > 0) {
                    existing.grow(move);
                    stack.shrink(move);
                }
            }
        }
        for (int i = 0; i < 36 && !stack.isEmpty(); i++) {
            if (inventory.getItem(i).isEmpty()) {
                int move = Math.min(stack.getMaxStackSize(), stack.getCount());
                inventory.setItem(i, stack.copyWithCount(move));
                stack.shrink(move);
            }
        }
        return stack;
    }

    private static BlockPos findNearbyCraftingTable(ServerPlayer player, double radius) {
        BlockPos origin = player.blockPosition();
        int r = (int) Math.ceil(radius);
        Vec3 eye = player.getEyePosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-r, -r, -r), origin.offset(r, r, r))) {
            if (!player.serverLevel().isLoaded(pos)) {
                continue;
            }
            if (!player.serverLevel().getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
                continue;
            }
            double distance = Vec3.atCenterOf(pos).distanceToSqr(eye);
            if (distance <= radius * radius && distance < bestDistance) {
                best = pos.immutable();
                bestDistance = distance;
            }
        }
        return best;
    }

    private static ContainerRef requireContainer(ServerPlayer player, Map<String, Object> params) {
        ServerLevel level = player.serverLevel();
        BlockPos pos = new BlockPos(intParam(params, "x"), intParam(params, "y"), intParam(params, "z"));
        if (!level.isLoaded(pos)) {
            throw new IllegalArgumentException("container position is not loaded");
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof Container container)) {
            throw new IllegalArgumentException("no container block entity at " + pos.toShortString());
        }
        Vec3 center = Vec3.atCenterOf(pos);
        requireBlockReach(player, center);
        if (!container.stillValid(player)) {
            throw new IllegalArgumentException("the container is blocked or no longer valid");
        }
        lookAtPoint(player, center);
        return new ContainerRef(pos, container);
    }

    private static int resolveContainerSlot(Container container, Map<String, Object> params) {
        if (params.containsKey("slot")) {
            return (int) clamp(num(params, "slot", 0.0D), 0.0D, container.getContainerSize() - 1);
        }
        String wanted = str(params, "item");
        if (wanted == null || wanted.isBlank()) {
            throw new IllegalArgumentException("pass slot or item to choose a container slot");
        }
        String needle = wanted.toLowerCase(Locale.ROOT).trim();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (id.contains(needle)) {
                return i;
            }
        }
        throw new IllegalArgumentException("no item matching '" + wanted + "' in the container");
    }

    private static boolean isLog(BlockState state) {
        return state.is(net.minecraft.tags.BlockTags.LOGS);
    }

    private static Map<String, Object> positionMap(BlockPos pos) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("x", pos.getX());
        map.put("y", pos.getY());
        map.put("z", pos.getZ());
        return map;
    }

    private static BlockPos findNearestLog(ServerPlayer player, ServerLevel level, double radius) {
        BlockPos center = player.blockPosition();
        int r = (int) Math.ceil(radius);
        Vec3 eye = player.getEyePosition();
        double bestDistance = Double.MAX_VALUE;
        BlockPos best = null;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-r, -r, -r), center.offset(r, r, r))) {
            Vec3 at = Vec3.atCenterOf(pos);
            double distance = at.distanceToSqr(eye);
            if (distance > radius * radius || distance >= bestDistance) {
                continue;
            }
            if (!level.isLoaded(pos)) {
                continue;
            }
            if (isLog(level.getBlockState(pos))) {
                best = pos.immutable();
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * Crafts an item using the vanilla recipes.
     *
     * <p>{@code count} is the desired output quantity. Ingredients are consumed
     * from the real inventory, 3x3 recipes require a crafting table within four
     * blocks, and output that does not fit is dropped at the companion's feet.</p>
     */
    private Map<String, Object> craftItem(Map<String, Object> params) {
        CompanionPlayer player = requireControl();
        String wanted = str(params, "item");
        if (wanted == null || wanted.isBlank()) {
            throw new IllegalArgumentException("item is required (result item id or substring)");
        }
        int desired = (int) clamp(num(params, "count", 1.0D), 1.0D, 256.0D);

        CraftingPlan plan = findCraftingRecipe(player, wanted);
        if (plan == null) {
            throw new IllegalArgumentException("no crafting recipe matches '" + wanted + "'");
        }
        RecipeHolder<CraftingRecipe> holder = plan.holder;
        CraftingRecipe recipe = holder.value();
        List<Ingredient> ingredients = recipe.getIngredients();

        ItemStack result = recipe.getResultItem(player.server.registryAccess()).copy();
        int perCraft = Math.max(1, result.getCount());
        int crafts = (desired + perCraft - 1) / perCraft;
        if (plan.crafts < crafts) {
            throw new IllegalArgumentException("not enough ingredients for " + desired
                    + "x " + wanted + " (can craft " + plan.crafts + " craft(s) of " + perCraft + ")");
        }
        boolean needsTable = !recipe.canCraftInDimensions(2, 2);
        BlockPos table = needsTable ? findNearbyCraftingTable(player, 4.0D) : null;
        if (needsTable && table == null) {
            throw new IllegalArgumentException("this recipe needs a crafting table within 4 blocks");
        }

        Inventory inventory = player.getInventory();
        int crafted = 0;
        for (int i = 0; i < crafts; i++) {
            if (!consumeIngredients(inventory, ingredients)) {
                break;
            }
            crafted++;
        }

        ItemStack produced = result.copyWithCount(perCraft * crafted);
        ItemStack leftover = insertIntoInventory(inventory, produced);
        if (!leftover.isEmpty()) {
            player.drop(leftover, false);
        }
        player.containerMenu.broadcastChanges();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", crafted > 0);
        out.put("recipe", holder.id().toString());
        out.put("item", BuiltInRegistries.ITEM.getKey(result.getItem()).toString());
        out.put("crafts", crafted);
        out.put("produced", perCraft * crafted);
        out.put("needs_crafting_table", needsTable);
        if (table != null) {
            out.put("crafting_table", positionMap(table));
        }
        if (!leftover.isEmpty()) {
            out.put("dropped_at_feet", leftover.getCount());
        }
        return out;
    }

    /**
     * Lists the recipes the companion can craft right now with the items in its
     * inventory, including how many times each one fits.
     */
    private Map<String, Object> getCraftable(Map<String, Object> params) {
        CompanionPlayer player = requireControl();
        String wanted = str(params, "item");
        String needle = wanted == null ? null : wanted.toLowerCase(Locale.ROOT).trim();
        int limit = (int) clamp(num(params, "limit", 40.0D), 1.0D, 200.0D);
        List<ItemStack> pool = snapshotIngredients(player.getInventory());
        List<Map<String, Object>> entries = new ArrayList<>();
        for (RecipeHolder<CraftingRecipe> holder : player.server.getRecipeManager()
                .getAllRecipesFor(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = holder.value();
            List<Ingredient> ingredients = recipe.getIngredients();
            if (ingredients.stream().allMatch(Ingredient::isEmpty)) {
                continue;
            }
            ItemStack result = recipe.getResultItem(player.server.registryAccess());
            if (result.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
            if (needle != null && !id.contains(needle)) {
                continue;
            }
            int possible = craftsPossible(pool, ingredients);
            if (possible <= 0) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("item", id);
            entry.put("count_per_craft", result.getCount());
            entry.put("crafts", possible);
            entry.put("max_output", result.getCount() * possible);
            entry.put("recipe", holder.id().toString());
            entry.put("needs_crafting_table", !recipe.canCraftInDimensions(2, 2));
            entries.add(entry);
        }
        entries.sort(java.util.Comparator.comparingInt(
                e -> -((Number) e.get("max_output")).intValue()));
        List<Map<String, Object>> limited = entries.size() > limit
                ? new ArrayList<>(entries.subList(0, limit))
                : entries;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("craftable", limited);
        out.put("count", limited.size());
        out.put("total_matches", entries.size());
        return out;
    }

    /** Moves one piece of armor from the inventory to its equipment slot. */
    private Map<String, Object> equipArmor(Map<String, Object> params) {
        CompanionPlayer player = requireControl();
        String wanted = str(params, "item");
        String needle = wanted == null ? null : wanted.toLowerCase(Locale.ROOT).trim();
        Inventory inventory = player.getInventory();
        int source = -1;
        ArmorItem armor = null;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof ArmorItem candidate)) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(candidate).toString();
            if (needle != null && !id.contains(needle)) {
                continue;
            }
            source = i;
            armor = candidate;
            break;
        }
        if (armor == null) {
            throw new IllegalArgumentException(needle == null
                    ? "there is no armor item in the inventory"
                    : "no armor matching '" + wanted + "' in the inventory");
        }

        EquipmentSlot slot = armor.getEquipmentSlot();
        ItemStack stack = inventory.getItem(source);
        ItemStack equipped = stack.copyWithCount(1);
        ItemStack previous = player.getItemBySlot(slot).copy();
        stack.shrink(1);
        if (stack.isEmpty()) {
            inventory.setItem(source, ItemStack.EMPTY);
        }
        player.setItemSlot(slot, equipped);
        if (!previous.isEmpty()) {
            ItemStack leftover = insertIntoInventory(inventory, previous);
            if (!leftover.isEmpty()) {
                player.drop(leftover, false);
            }
        }
        player.containerMenu.broadcastChanges();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("item", BuiltInRegistries.ITEM.getKey(armor).toString());
        out.put("slot", slot.getName());
        out.put("source_slot", source);
        out.put("replaced", previous.isEmpty() ? null
                : BuiltInRegistries.ITEM.getKey(previous.getItem()).toString());
        out.put("equipment", equipment());
        return out;
    }

    /** Selects a hotbar slot (0-8) and reports the held item. */
    private Map<String, Object> selectHotbar(Map<String, Object> params) {
        CompanionPlayer player = requireControl();
        Inventory inventory = player.getInventory();
        int slot = (int) clamp(num(params, "slot", inventory.selected), 0.0D, 8.0D);
        inventory.selected = slot;
        player.containerMenu.broadcastChanges();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("selected_slot", slot);
        out.put("item", stackInfo(slot, inventory.getItem(slot)));
        return out;
    }

    /** Drops items from the inventory as the player would with the drop key. */
    private Map<String, Object> dropItem(Map<String, Object> params) {
        CompanionPlayer player = requireControl();
        Inventory inventory = player.getInventory();
        int slot = -1;
        if (params.containsKey("slot")) {
            slot = (int) clamp(num(params, "slot", 0.0D), 0.0D, 35.0D);
        } else {
            String wanted = str(params, "item");
            String needle = wanted == null ? null : wanted.toLowerCase(Locale.ROOT).trim();
            for (int i = 0; i < 36; i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.isEmpty()) {
                    continue;
                }
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (needle == null ? !stack.isEmpty() : id.contains(needle)) {
                    slot = i;
                    break;
                }
            }
        }
        if (slot < 0 || inventory.getItem(slot).isEmpty()) {
            throw new IllegalArgumentException("no matching item in the inventory");
        }
        ItemStack stack = inventory.getItem(slot);
        int count = (int) clamp(num(params, "count", stack.getCount()), 1.0D, stack.getCount());
        ItemStack dropped = stack.copyWithCount(count);
        stack.shrink(count);
        if (stack.isEmpty()) {
            inventory.setItem(slot, ItemStack.EMPTY);
        }
        player.drop(dropped, false);
        player.containerMenu.broadcastChanges();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("item", BuiltInRegistries.ITEM.getKey(dropped.getItem()).toString());
        out.put("count", dropped.getCount());
        out.put("slot", slot);
        out.put("remaining", inventory.getItem(slot).getCount());
        return out;
    }

    /** Lists the slots of a chest/barrel/other container block in reach. */
    private Map<String, Object> containerList(Map<String, Object> params) {
        CompanionPlayer player = requireControl();
        ContainerRef ref = requireContainer(player, params);
        boolean includeEmpty = bool(params, "include_empty", false);
        int limit = (int) clamp(num(params, "limit", 64.0D), 1.0D, 256.0D);
        List<Map<String, Object>> slots = new ArrayList<>();
        for (int i = 0; i < ref.container.getContainerSize() && slots.size() < limit; i++) {
            ItemStack stack = ref.container.getItem(i);
            if (!includeEmpty && stack.isEmpty()) {
                continue;
            }
            slots.add(stackInfo(i, stack));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("container", BuiltInRegistries.BLOCK.getKey(
                player.serverLevel().getBlockState(ref.pos).getBlock()).toString());
        out.put("position", positionMap(ref.pos));
        out.put("size", ref.container.getContainerSize());
        out.put("slots", slots);
        out.put("count", slots.size());
        return out;
    }

    /** Takes items out of a container into the companion's inventory. */
    private Map<String, Object> containerTake(Map<String, Object> params) {
        CompanionPlayer player = requireControl();
        ContainerRef ref = requireContainer(player, params);
        int slot = resolveContainerSlot(ref.container, params);
        ItemStack current = ref.container.getItem(slot);
        if (current.isEmpty()) {
            throw new IllegalArgumentException("container slot " + slot + " is empty");
        }
        int count = (int) clamp(num(params, "count", current.getCount()), 1.0D, current.getCount());
        ItemStack taken = ref.container.removeItem(slot, count);
        if (ref.container instanceof BlockEntity blockEntity) {
            blockEntity.setChanged();
        }
        ItemStack leftover = insertIntoInventory(player.getInventory(), taken.copy());
        if (!leftover.isEmpty()) {
            player.drop(leftover, false);
        }
        player.containerMenu.broadcastChanges();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("item", BuiltInRegistries.ITEM.getKey(taken.getItem()).toString());
        out.put("count", taken.getCount());
        out.put("container_slot", slot);
        if (!leftover.isEmpty()) {
            out.put("dropped_at_feet", leftover.getCount());
        }
        return out;
    }

    /** Puts items from the companion's inventory into a container. */
    private Map<String, Object> containerPut(Map<String, Object> params) {
        CompanionPlayer player = requireControl();
        ContainerRef ref = requireContainer(player, params);
        Inventory inventory = player.getInventory();
        int source = -1;
        String wanted = str(params, "item");
        String needle = wanted == null ? null : wanted.toLowerCase(Locale.ROOT).trim();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (needle == null || id.contains(needle)) {
                source = i;
                break;
            }
        }
        if (source < 0) {
            throw new IllegalArgumentException("no matching item in the inventory");
        }
        ItemStack sourceStack = inventory.getItem(source);
        String movedId = BuiltInRegistries.ITEM.getKey(sourceStack.getItem()).toString();
        int count = (int) clamp(num(params, "count", sourceStack.getCount()), 1.0D, sourceStack.getCount());
        int targetSlot = -1;
        if (params.containsKey("container_slot")) {
            targetSlot = (int) clamp(num(params, "container_slot", 0.0D),
                    0.0D, ref.container.getContainerSize() - 1);
        } else {
            for (int i = 0; i < ref.container.getContainerSize(); i++) {
                ItemStack existing = ref.container.getItem(i);
                if (!existing.isEmpty()
                        && ItemStack.isSameItemSameComponents(existing, sourceStack)
                        && existing.getCount() < existing.getMaxStackSize()) {
                    targetSlot = i;
                    break;
                }
            }
            if (targetSlot < 0) {
                for (int i = 0; i < ref.container.getContainerSize(); i++) {
                    if (ref.container.getItem(i).isEmpty()) {
                        targetSlot = i;
                        break;
                    }
                }
            }
        }
        if (targetSlot < 0) {
            throw new IllegalArgumentException("the container has no free slot");
        }

        ItemStack moving = sourceStack.copyWithCount(count);
        ItemStack target = ref.container.getItem(targetSlot);
        if (!target.isEmpty()) {
            if (!ItemStack.isSameItemSameComponents(target, moving)) {
                throw new IllegalArgumentException("container slot " + targetSlot
                        + " already holds a different item");
            }
            int room = target.getMaxStackSize() - target.getCount();
            if (room <= 0) {
                throw new IllegalArgumentException("container slot " + targetSlot + " is full");
            }
            int move = Math.min(room, moving.getCount());
            target.grow(move);
            moving.shrink(move);
            ref.container.setItem(targetSlot, target);
        } else {
            ref.container.setItem(targetSlot, moving.copy());
            moving.setCount(0);
        }
        sourceStack.shrink(count - moving.getCount());
        if (sourceStack.isEmpty()) {
            inventory.setItem(source, ItemStack.EMPTY);
        }
        if (!moving.isEmpty()) {
            // Not everything fit; put the remainder back where it came from.
            ItemStack leftover = insertIntoInventory(inventory, moving);
            if (!leftover.isEmpty()) {
                player.drop(leftover, false);
            }
        }
        if (ref.container instanceof BlockEntity blockEntity) {
            blockEntity.setChanged();
        }
        player.containerMenu.broadcastChanges();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("item", movedId);
        out.put("moved", count - moving.getCount());
        out.put("container_slot", targetSlot);
        out.put("source_slot", source);
        return out;
    }

    /** Uses the held item on an entity (shears, bucket, name tag, breeding...). */
    private Map<String, Object> useOnEntity(Map<String, Object> params) {
        CompanionPlayer player = requireControl();
        String query = str(params, "entity");
        double radius = clamp(num(params, "radius", 8.0D), 1.0D, 64.0D);
        Entity target = findEntity(player, query, radius);
        if (target == null) {
            throw new IllegalArgumentException("no entity matching '" + query
                    + "' within " + (int) radius + " blocks");
        }
        Vec3 point = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        double reach = player.entityInteractionRange() + 0.5D;
        double distance = Math.sqrt(player.getEyePosition().distanceToSqr(point));
        if (distance > reach) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "entity is %.2f blocks away; walk closer (max %.2f)", distance, reach));
        }
        ItemStack held = player.getMainHandItem().copy();
        lookAtPoint(player, point);
        InteractionResult result = player.interactOn(target, InteractionHand.MAIN_HAND);
        player.swing(InteractionHand.MAIN_HAND, true);
        player.containerMenu.broadcastChanges();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", result.consumesAction());
        out.put("result", result.name().toLowerCase(Locale.ROOT));
        out.put("target", entityInfo(target, player));
        out.put("item_before", BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
        out.put("item_after", BuiltInRegistries.ITEM.getKey(
                player.getMainHandItem().getItem()).toString());
        out.put("count_after", player.getMainHandItem().getCount());
        return out;
    }

    private Map<String, Object> actionStatus(Map<String, Object> params) {
        String id = str(params, "id");
        Map<String, Object> action = id == null ? null : this.actions.get(id);
        if (action == null) {
            throw new IllegalArgumentException("unknown action id: " + id);
        }
        return new LinkedHashMap<>(action);
    }

    private Map<String, Object> cancelAction(Map<String, Object> params) {
        String id = str(params, "id");
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        boolean cancelled = cancelActive(id);
        if (!cancelled) {
            Map<String, Object> action = this.actions.get(id);
            if (action == null) {
                throw new IllegalArgumentException("unknown action id: " + id);
            }
            action.put("state", "cancelled");
        }
        return Map.of("ok", true, "cancelled", true);
    }

    private Map<String, Object> emergencyStop() {
        this.emergency = true;
        cancelActive("cancelled");
        this.companion.controller().stop();
        return Map.of("ok", true, "emergency_stop", true);
    }

    private Map<String, Object> resetEmergencyStop() {
        this.emergency = false;
        return Map.of("ok", true, "emergency_stop", false);
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    private Map<String, Object> spawnCompanion(Map<String, Object> params) {
        MinecraftServer server = requireServer();
        boolean spawned = this.companion.spawn(server);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", spawned || this.companion.isPresent());
        map.put("spawned", spawned);
        map.put("companion", this.companion.state());
        return map;
    }

    private Map<String, Object> despawnCompanion(Map<String, Object> params) {
        MinecraftServer server = requireServer();
        this.companion.despawn(server);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", true);
        map.put("companion", this.companion.state());
        return map;
    }

    private Map<String, Object> respawnCompanion(Map<String, Object> params) {
        MinecraftServer server = requireServer();
        boolean spawned = this.companion.respawn(server);
        CompanionPlayer player = this.companion.player();
        if (player != null) {
            respawnGreeting(player);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", spawned || this.companion.isPresent());
        map.put("spawned", spawned);
        map.put("companion", this.companion.state());
        return map;
    }

    // ------------------------------------------------------------------
    // Autopilot / reflexes / batch
    // ------------------------------------------------------------------

    /** Enables or disables the automatic behaviours. */
    private Map<String, Object> setAutopilot(Map<String, Object> params) {
        if (params.containsKey("enabled")
                && !params.containsKey("chat") && !params.containsKey("eat")
                && !params.containsKey("defend")) {
            boolean value = bool(params, "enabled", true);
            this.reflexChat = value;
            this.autoEat = value;
            this.autoDefend = value;
        }
        if (params.containsKey("chat")) {
            this.reflexChat = bool(params, "chat", true);
        }
        if (params.containsKey("eat")) {
            this.autoEat = bool(params, "eat", true);
        }
        if (params.containsKey("defend")) {
            this.autoDefend = bool(params, "defend", false);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("autopilot", autopilotState());
        return out;
    }

    public Map<String, Object> autopilotState() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("chat_reflexes", this.reflexChat);
        map.put("auto_eat", this.autoEat);
        map.put("auto_defend", this.autoDefend);
        map.put("mode", this.stance.name());
        map.put("lively", this.lively);
        return map;
    }

    /**
     * Runs several tool calls in one MCP request, which removes most of the
     * round-trip latency for simple tasks.
     *
     * <p>Async actions ({@code move}, {@code goto}, {@code follow}) replace each
     * other, so at most one of them should be used, normally last. Instant
     * actions ({@code break_block}, {@code place_block}, {@code craft_item},
     * {@code eat}, {@code attack}, {@code drop_item}, {@code send_chat}...) all
     * run in order.</p>
     */
    private Map<String, Object> batch(Map<String, Object> params) {
        Object raw = params.get("actions");
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("actions must be an array of {tool, params}");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int ok = 0;
        int failed = 0;
        for (Object item : list) {
            Map<String, Object> entry = new LinkedHashMap<>();
            if (!(item instanceof Map<?, ?> action)) {
                entry.put("error", "each action must be an object");
                results.add(entry);
                failed++;
                continue;
            }
            Object toolValue = action.get("tool");
            String tool = toolValue == null ? null : String.valueOf(toolValue);
            entry.put("tool", tool);
            if (tool == null || tool.isBlank()) {
                entry.put("error", "missing tool name");
                results.add(entry);
                failed++;
                continue;
            }
            if ("batch".equals(tool)) {
                entry.put("error", "batch cannot be nested");
                results.add(entry);
                failed++;
                continue;
            }
            if (this.dispatcher == null) {
                entry.put("error", "dispatcher is not ready");
                results.add(entry);
                failed++;
                continue;
            }
            Map<String, Object> callParams = new LinkedHashMap<>();
            if (action.get("params") instanceof Map<?, ?> given) {
                for (Map.Entry<?, ?> callEntry : given.entrySet()) {
                    callParams.put(String.valueOf(callEntry.getKey()), callEntry.getValue());
                }
            }
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("method", tool);
            request.put("params", callParams);
            Map<String, Object> response;
            this.inBatch = true;
            try {
                response = this.dispatcher.dispatch(request);
            } finally {
                this.inBatch = false;
            }
            if (response.containsKey("result")) {
                entry.put("result", response.get("result"));
                ok++;
            } else {
                entry.put("error", response.get("error"));
                failed++;
            }
            results.add(entry);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", failed == 0);
        out.put("ran", results.size());
        out.put("succeeded", ok);
        out.put("failed", failed);
        out.put("results", results);
        return out;
    }

    /**
     * Per-tick reflexes that need no model round-trip: obey simple chat orders,
     * eat when hungry and (optionally) fight nearby monsters. This is what makes
     * the companion feel responsive instead of waiting for the next MCP call.
     */
    private void autopilotTick(MinecraftServer server) {
        if (this.emergency) {
            return;
        }
        CompanionPlayer player = this.companion.player();
        if (player == null || !player.isAlive()) {
            return;
        }
        this.autopilotTicks++;
        if (!this.autopilotReady) {
            this.autopilotReady = true;
            // Ignore everything said before the mod started: only react to
            // messages that arrive while the companion is live.
            this.lastChatSequence = this.chatLog.latestSequence();
        }
        if (this.reflexChat) {
            readChatReflexes(player);
        }
        if (this.lively) {
            livelyTick(player);
        }
        combatTick(player);
        stuckTick(player);
        durabilityTick(player);
        inventoryTick(player);
        pendingGiveTick(player);
        if (this.autoEat && this.autopilotTicks % 20L == 0L) {
            maybeAutoEat(player);
        }
        if (this.autoDefend && this.autopilotTicks % 10L == 0L) {
            maybeAutoDefend(player);
        }
        if (this.followResume && this.activeTask == null
                && this.autopilotTicks >= this.followResumeAt) {
            ServerPlayer target = this.followTarget == null ? null
                    : player.server.getPlayerList().getPlayerByName(this.followTarget);
            if (target != null) {
                this.followResume = false;
                startTask(new FollowTask(newAction("follow", "running"),
                        this.followTarget, this.followDistance), player);
            }
        }
        idleChatTick(player);
        if (!this.welcomed) {
            welcomeTick(player);
        }
    }

    // ------------------------------------------------------------------
    // Combat: real self-defence and "defendeme" (guard another player)
    // ------------------------------------------------------------------

    /**
     * Runs every tick. Never writes to chat. Entering combat is automatic when
     * the companion is hurt or a hostile is close, or when the player asked to
     * be defended ("defendeme", "protegeme", "cuidame").
     */
    private void combatTick(CompanionPlayer player) {
        if (this.emergency) {
            return;
        }
        float hp = player.getHealth();
        if (hp < this.lastHpSeen - 0.01F) {
            this.lastHurtTick = this.autopilotTicks;
        }
        this.lastHpSeen = hp;

        Entity target = pickCombatTarget(player);
        if (target == null || !target.isAlive() || target.level() != player.level()) {
            stopCombatDrive(player);
            this.combatTarget = null;
            return;
        }
        this.combatTarget = target;
        double distance = Math.sqrt(player.distanceToSqr(target));
        double reach = player.entityInteractionRange() + 0.6D;

        if (distance <= reach) {
            stopCombatDrive(player);
            lookAtPoint(player, target.getEyePosition());
            combatSwing(player, target);
            return;
        }
        // Harmony: never steal movement or the camera from a running task.
        // Follow and other tasks keep their own pathfinding; combat only looks
        // and closes the gap when the companion is idle.
        if (this.activeTask == null) {
            lookAtPoint(player, target.getEyePosition());
            combatChase(player, target, distance);
        }
    }

    private Entity pickCombatTarget(CompanionPlayer player) {
        ServerPlayer guarded = this.defendPlayer == null ? null
                : player.server.getPlayerList().getPlayerByName(this.defendPlayer);
        if (guarded != null) {
            Entity threat = nearestHostile(guarded, 14.0D);
            if (threat != null) {
                return threat;
            }
        }
        boolean hurt = this.autopilotTicks - this.lastHurtTick < 120L;
        double radius = hurt ? 16.0D : Math.max(this.stance.enemyRange(), 8.0D);
        return nearestHostile(player, radius);
    }

    /** Closest monster around {@code around}. Creepers are skipped: meleeing one is suicide. */
    private static Entity nearestHostile(ServerPlayer around, double radius) {
        Entity best = null;
        double bestDistance = radius * radius;
        for (Entity entity : around.serverLevel().getEntities(around,
                around.getBoundingBox().inflate(radius))) {
            if (!(entity instanceof net.minecraft.world.entity.monster.Monster)) {
                continue;
            }
            if (!entity.isAlive() || entity instanceof net.minecraft.world.entity.monster.Creeper) {
                continue;
            }
            double distance = entity.distanceToSqr(around);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }

    /**
     * Charged swing with real critical hits: jump, then hit while falling with
     * the attack strength bar full. Never spams: each swing waits for the
     * vanilla cooldown.
     */
    private void combatSwing(CompanionPlayer player, Entity target) {
        float charge = player.getAttackStrengthScale(0.5F);
        if (charge < 0.9F) {
            return;
        }
        if (this.autopilotTicks - this.lastCombatSwing < 6L) {
            return;
        }
        boolean falling = !player.onGround()
                && player.getDeltaMovement().y < 0.0D
                && player.fallDistance > 0.0F
                && !player.onClimbable()
                && !player.isInWater();
        if (player.onGround()) {
            // Jump first; the next tick in the air becomes the critical hit.
            if (this.autopilotTicks - this.combatJumpTick > 8L) {
                player.setSprinting(false);
                this.companion.controller().jump();
                this.combatJumpTick = this.autopilotTicks;
            }
            return;
        }
        if (!falling) {
            return;
        }
        player.setSprinting(false); // sprinting cancels vanilla critical hits
        lookAtPoint(player, target.getEyePosition());
        player.attackWithFullStrength(target);
        player.swing(InteractionHand.MAIN_HAND, true);
        player.containerMenu.broadcastChanges();
        this.lastCombatSwing = this.autopilotTicks;
    }

    /** Walks toward a hostile using the vanilla movement inputs. */
    private void combatChase(CompanionPlayer player, Entity target, double distance) {
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        this.companion.controller().look(yaw, 0.0F);
        this.companion.controller().move(1.0D, 0.0D, distance > 4.0D, 4);
        this.combatDriving = true;
        if (player.onGround() && player.horizontalCollision) {
            this.companion.controller().jump();
        }
    }

    private void stopCombatDrive(CompanionPlayer player) {
        if (this.combatDriving) {
            this.companion.controller().stop();
            this.combatDriving = false;
        }
    }

    // ------------------------------------------------------------------
    // State reflexes: stuck, durability, inventory, welcome
    // ------------------------------------------------------------------

    /**
     * Detects real physical blocking: a task is driving the body and the
     * position does not change for two seconds. Two recovery attempts, then
     * cancel the task and say it out loud.
     */
    private void stuckTick(CompanionPlayer player) {
        if (this.autopilotTicks % 20L != 0L) {
            return;
        }
        if (this.emergency || this.activeTask == null
                || !this.companion.controller().isMoving()) {
            this.stuckSinceTick = -1L;
            this.stuckRecoveries = 0;
            this.stuckLastX = player.getX();
            this.stuckLastZ = player.getZ();
            return;
        }
        double dx = player.getX() - this.stuckLastX;
        double dz = player.getZ() - this.stuckLastZ;
        if (Math.sqrt(dx * dx + dz * dz) >= 0.6D) {
            this.stuckSinceTick = -1L;
            this.stuckRecoveries = 0;
            this.stuckLastX = player.getX();
            this.stuckLastZ = player.getZ();
            return;
        }
        if (this.stuckSinceTick < 0L) {
            this.stuckSinceTick = this.autopilotTicks;
            return;
        }
        if (this.autopilotTicks - this.stuckSinceTick < 40L) {
            return;
        }
        this.stuckSinceTick = this.autopilotTicks;
        this.stuckLastX = player.getX();
        this.stuckLastZ = player.getZ();
        if (this.stuckRecoveries < 2) {
            this.stuckRecoveries++;
            this.companion.controller().jump();
            this.companion.controller().move(0.4D,
                    this.stuckRecoveries == 1 ? 0.8D : -0.8D, false, 10);
            return;
        }
        cancelActive("stuck");
        this.stuckRecoveries = 0;
        this.stuckSinceTick = -1L;
        if (this.autopilotTicks - this.lastStuckNotice > 600L) {
            this.lastStuckNotice = this.autopilotTicks;
            reply("me quede trabado aca, no encuentro la salida");
        }
    }

    /**
     * Swaps a tool before it breaks. Warns once per item when there is no
     * compatible spare, and never swaps for a worse one.
     */
    private void durabilityTick(CompanionPlayer player) {
        if (this.autopilotTicks % 20L != 0L) {
            return;
        }
        ItemStack hand = player.getMainHandItem();
        if (hand.isEmpty() || !hand.isDamageableItem() || !isTool(hand.getItem())) {
            return;
        }
        int max = hand.getMaxDamage();
        if (max <= 0) {
            return;
        }
        int left = max - hand.getDamageValue();
        if (left > max * 0.15D) {
            this.warnedTools.remove(hand.getItem());
            return;
        }
        int replacement = findCompatibleTool(player, hand);
        if (replacement < 0) {
            if (this.warnedTools.add(hand.getItem())
                    && this.autopilotTicks - this.lastDurabilityNotice > 200L) {
                this.lastDurabilityNotice = this.autopilotTicks;
                reply("mi herramienta esta por romperse y no tengo repuesto");
            }
            return;
        }
        player.getInventory().selected = replacement;
        player.containerMenu.broadcastChanges();
        this.warnedTools.remove(hand.getItem());
        if (this.autopilotTicks - this.lastDurabilityNotice > 200L) {
            this.lastDurabilityNotice = this.autopilotTicks;
            reply("mi herramienta esta gastada, la cambio por otra");
        }
    }

    /** True for items used to dig or fight, so blocks and food are ignored. */
    private static boolean isTool(Item item) {
        return item instanceof net.minecraft.world.item.DiggerItem
                || item instanceof net.minecraft.world.item.SwordItem;
    }

    /** Same category (pickaxe/axe/shovel/sword) with more remaining durability. */
    private static int findCompatibleTool(ServerPlayer player, ItemStack current) {
        Item wanted = current.getItem();
        double currentLeft = current.getMaxDamage() - current.getDamageValue();
        int bestSlot = -1;
        double bestLeft = currentLeft;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack candidate = player.getInventory().getItem(slot);
            if (candidate.isEmpty() || candidate == current || !candidate.isDamageableItem()) {
                continue;
            }
            if (!sameToolCategory(wanted, candidate.getItem())) {
                continue;
            }
            double candidateLeft = candidate.getMaxDamage() - candidate.getDamageValue();
            if (candidateLeft > bestLeft) {
                bestLeft = candidateLeft;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private static boolean sameToolCategory(Item a, Item b) {
        if (a == b) {
            return true;
        }
        return (a instanceof net.minecraft.world.item.PickaxeItem
                    && b instanceof net.minecraft.world.item.PickaxeItem)
                || (a instanceof net.minecraft.world.item.AxeItem
                    && b instanceof net.minecraft.world.item.AxeItem)
                || (a instanceof net.minecraft.world.item.ShovelItem
                    && b instanceof net.minecraft.world.item.ShovelItem)
                || (a instanceof net.minecraft.world.item.SwordItem
                    && b instanceof net.minecraft.world.item.SwordItem);
    }

    /** Stops gathering when the inventory really cannot hold more, without dropping anything. */
    private void inventoryTick(CompanionPlayer player) {
        if (this.autopilotTicks % 40L != 0L) {
            return;
        }
        boolean gathering = this.activeTask instanceof GatherTask;
        if (!gathering) {
            this.inventoryFullWarned = false;
            return;
        }
        int empty = 0;
        for (int slot = 0; slot < 36; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) {
                empty++;
            }
        }
        if (empty > 2) {
            this.inventoryFullWarned = false;
            return;
        }
        if (this.inventoryFullWarned) {
            return;
        }
        this.inventoryFullWarned = true;
        cancelActive("inventory_full");
        reply("tengo el inventario lleno, no puedo recoger mas");
    }

    /** One greeting per spawn / respawn, never on every mod rebuild. */
    private void welcomeTick(CompanionPlayer player) {
        if (this.welcomed || this.autopilotTicks % 20L != 0L
                || this.autopilotTicks - this.lastWelcomeTick < 200L) {
            return;
        }
        ServerPlayer human = nearestHuman(player, 32.0D);
        if (human == null) {
            return;
        }
        this.welcomed = true;
        this.lastWelcomeTick = this.autopilotTicks;
        lookAtPoint(player, human.getEyePosition());
        reply("buenas " + human.getGameProfile().getName() + "! ya estoy aca");
    }

    /** Called by respawn so the player knows the companion is back. */
    private void respawnGreeting(CompanionPlayer player) {
        if (this.autopilotTicks - this.lastWelcomeTick < 200L) {
            return;
        }
        this.lastWelcomeTick = this.autopilotTicks;
        this.welcomed = true;
        ServerPlayer human = nearestHuman(player, 32.0D);
        reply(human == null ? "ya volvi" : "ya volvi, " + human.getGameProfile().getName());
    }

    /**
     * Occasional idle line so the companion keeps the conversation alive
     * instead of going silent while the agent thinks.
     */
    private void idleChatTick(CompanionPlayer player) {
        if (!this.lively || this.activeTask != null || this.pendingGiveItem != null) {
            return;
        }
        if (this.autopilotTicks - this.lastIdleChatTick < 1200L) {
            return;
        }
        ServerPlayer near = nearestHuman(player, 10.0D);
        if (near == null || this.chatLog.latestSequence() != this.lastChatSequence) {
            return;
        }
        this.lastIdleChatTick = this.autopilotTicks;
        String[] lines = {
                "che, que lindo lugar",
                "y ahora que hacemos?",
                "esto pinta bien",
                "avisame si ves algo raro",
                "tengo ganas de encontrar hierro",
                "vos mandas, yo te sigo"
        };
        reply(lines[(int) (this.autopilotTicks / 1200L % lines.length)]);
    }

    private boolean isNight(ServerLevel level) {
        long t = level.getDayTime() % 24000L;
        return t >= 13000L && t < 23000L;
    }

    /**
     * Arms the interrupts for the task that was just started. Pass
     * {@code interrupts:false} to disable them or {@code ignore:["enemy","chat",
     * "night","health","hazard"]} to skip categories (e.g. after deciding to keep
     * working despite an enemy).
     */
    private void armInterrupts(Map<String, Object> params, CompanionPlayer player) {
        if (!bool(params, "interrupts", true)) {
            return;
        }
        Set<String> ignore = new HashSet<>();
        for (String entry : stringList(params.get("ignore"))) {
            ignore.add(entry.toLowerCase(Locale.ROOT).trim());
        }
        this.interruptIgnore = ignore;
        this.interruptChatBase = this.chatLog.latestSequence();
        this.interruptStartNight = isNight(player.serverLevel());
        this.interruptHp = player.getHealth();
        this.interruptOn = true;
    }

    private String interrupted(String reason, Object... keyValues) {
        Map<String, Object> extra = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            extra.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        this.interruptExtra = extra;
        return reason;
    }

    /**
     * Returns why the running task should stop now, or null. Reasons:
     * player_chat, low_health, taking_damage, in_lava, on_fire, drowning,
     * night_fell, enemy_near. The status keeps the task progress and adds the
     * details (the chat lines, the enemy...) so the model can decide in one step.
     */
    private String checkInterrupt(CompanionPlayer player) {
        if (!this.interruptOn || this.emergency) {
            return null;
        }
        if (!this.interruptIgnore.contains("chat")) {
            long latest = this.chatLog.latestSequence();
            if (latest > this.interruptChatBase) {
                List<Map<String, Object>> lines = new ArrayList<>();
                for (ChatLog.Entry entry : this.chatLog.since(this.interruptChatBase, 8)) {
                    if (entry.sequence() <= this.interruptChatBase || entry.fromCompanion()
                            || !"chat".equals(entry.type())) {
                        continue;
                    }
                    Map<String, Object> line = new LinkedHashMap<>();
                    line.put("from", String.valueOf(entry.sender()));
                    line.put("msg", String.valueOf(entry.message()));
                    lines.add(line);
                }
                this.interruptChatBase = latest;
                if (!lines.isEmpty()) {
                    // The lines travel in the status, so do not repeat them as new_chat.
                    if (this.chatPushed >= 0L && latest > this.chatPushed) {
                        this.chatPushed = latest;
                    }
                    return interrupted("player_chat", "chat", lines);
                }
            }
        }
        float hp = player.getHealth();
        if (hp > this.interruptHp) {
            this.interruptHp = hp;
        }
        if (!this.interruptIgnore.contains("health")) {
            if (hp <= this.stance.lowHp()) {
                return interrupted("low_health", "hp", round1(hp));
            }
            if (this.interruptHp - hp >= this.stance.damageDrop()) {
                return interrupted("taking_damage", "hp", round1(hp));
            }
        }
        if (!this.interruptIgnore.contains("hazard")) {
            if (player.isInLava()) {
                return interrupted("in_lava");
            }
            if (player.isOnFire()) {
                return interrupted("on_fire");
            }
            if (player.isInWater() && player.getAirSupply() < 80) {
                return interrupted("drowning", "air", player.getAirSupply());
            }
            if (this.stance.lavaWatch() && this.autopilotTicks % 10L == 0L && lavaNear(player)) {
                return interrupted("lava_near");
            }
        }
        if (!this.interruptIgnore.contains("night") && this.stance.nightInterrupt() && !this.interruptStartNight
                && isNight(player.serverLevel())) {
            return interrupted("night_fell");
        }
        if (!this.interruptIgnore.contains("enemy") && !this.autoDefend
                && this.autopilotTicks % 5L == 0L) {
            Entity monster = nearestMonster(player, this.stance.enemyRange());
            if (monster != null) {
                return interrupted("enemy_near",
                        "enemy", shortName(BuiltInRegistries.ENTITY_TYPE.getKey(monster.getType()).toString()),
                        "dist", round1(Math.sqrt(monster.distanceToSqr(player))));
            }
        }
        return null;
    }

    private void readChatReflexes(CompanionPlayer player) {
        List<ChatLog.Entry> messages = this.chatLog.since(this.lastChatSequence, 16);
        if (messages.isEmpty()) {
            return;
        }
        for (ChatLog.Entry entry : messages) {
            if (entry.sequence() <= this.lastChatSequence) {
                continue;
            }
            this.lastChatSequence = entry.sequence();
            if (entry.fromCompanion() || !"chat".equals(entry.type())) {
                continue;
            }
            faceSpeaker(player, entry.sender());
            if (reactToCommand(player, entry.sender(), entry.message())) {
                // One order per tick is enough; the rest is read next tick.
                return;
            }
        }
    }

    private boolean reactToCommand(CompanionPlayer player, String sender, String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String text = normalizeCommand(raw);
        java.util.Set<String> words = new java.util.HashSet<>();
        for (String word : text.split("\\s+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        try {
            if (isSleepCommand(text, words)) {
                return sleepReflex(player);
            }

            if (text.contains("no me defiendas") || text.contains("deja de defenderme")
                    || text.contains("deja de protegerme") || text.contains("no me protejas")
                    || text.contains("deja de cuidarme") || text.contains("dejame tranquilo")) {
                if (this.defendPlayer != null) {
                    this.defendPlayer = null;
                    reply("ok, bajo la guardia");
                    return true;
                }
            }

            if (isStopCommand(text, words)) {
                cancelActive("chat_stop");
                clearGive();
                this.defendPlayer = null;
                this.followTarget = null;
                this.followResume = false;
                this.combatTarget = null;
                this.companion.controller().stop();
                reply("listo, me quedo quieto");
                return true;
            }

            if (words.contains("defendeme") || words.contains("defiendeme")
                    || words.contains("defenderme") || words.contains("protegeme")
                    || words.contains("protegerme") || words.contains("cuidame")
                    || words.contains("cuidarme") || words.contains("cubrime")
                    || words.contains("cubrirme") || words.contains("escoltame")) {
                boolean already = sender.equals(this.defendPlayer);
                this.defendPlayer = sender;
                // Guarding means staying close: the follow order is persistent
                // but never replaces a chore that is already running.
                if (this.followTarget == null) {
                    this.followTarget = sender;
                    this.followDistance = 3.0D;
                }
                if (this.activeTask == null) {
                    startTask(new FollowTask(newAction("follow", "running"),
                            sender, this.followDistance), player);
                }
                if (!already && this.autopilotTicks - this.lastDefendReply > 60L) {
                    this.lastDefendReply = this.autopilotTicks;
                    reply("dale, te cubro");
                }
                return true;
            }

            if (words.contains("defendete") || words.contains("defiendete")
                    || words.contains("defenderse")) {
                // Self-defence is always on; acknowledge once, never spam.
                if (this.autopilotTicks - this.lastDefendReply > 1200L) {
                    this.lastDefendReply = this.autopilotTicks;
                    reply("quedate tranquilo, me defiendo solo");
                }
                return true;
            }

            if (isSavePlaceOrder(text)) {
                return savePlaceReflex(player, sender, text);
            }

            if (recallPlaceReflex(player, text)) {
                return true;
            }

            // --- Chat intent filter -------------------------------------
            // Questions should not fire orders unless they are polite
            // requests ("¿me das arena?", "¿podés venir?"). Negations
            // ("no me sigas") cancel instead of activating.
            boolean question = looksLikeQuestion(text);
            if (question && !isRequestQuestion(text)
                    && !words.contains("hola") && !words.contains("buenas")
                    && !words.contains("gracias")) {
                return false;
            }
            if (text.contains("no me sigas") || text.contains("no me siga")
                    || text.contains("no vengas") || text.contains("no me acompanes")
                    || text.contains("no te acerques")
                    || text.contains("no quiero que me sigas")) {
                this.followTarget = null;
                this.followResume = false;
                cancelActive("chat_stop");
                reply("ok, no te sigo");
                return true;
            }

            if (words.contains("cauteloso") || words.contains("precavido")
                    || (words.contains("cuidado") && (words.contains("ten") || words.contains("tene")))) {
                setStance("cauteloso");
                reply("ok, voy con cuidado");
                return true;
            }
            if (words.contains("valiente") || words.contains("audaz") || text.contains("sin miedo")) {
                setStance("valiente");
                reply("dale, sin miedo");
                return true;
            }
            if (words.contains("modo") && words.contains("normal")) {
                setStance("normal");
                reply("vuelvo a lo normal");
                return true;
            }
            if (words.contains("dame") || words.contains("pasame") || words.contains("damelo")
                    || words.contains("pasalo") || words.contains("entregame")
                    || words.contains("tira") || words.contains("tirame") || words.contains("tirarme")
                    || words.contains("dropea") || words.contains("dropeame")
                    || words.contains("droparme") || words.contains("suelta") || words.contains("soltame")
                    || words.contains("larga") || words.contains("largame")) {
                if (text.contains("no me des") || text.contains("no me pases")
                        || text.contains("no me tires") || text.contains("no me dropes")
                        || text.contains("no me sueltes")) {
                    return true;
                }
                return giveReflex(player, sender, words, text);
            }

            if (words.contains("tala") || words.contains("talar") || words.contains("talo")
                    || words.contains("chopea") || words.contains("chop")
                    || ((words.contains("corta") || words.contains("cortar"))
                        && words.contains("arbol"))) {
                chopTree(Map.of("max_logs", 64, "radius", 16));
                reply("dale, talo el arbol mas cercano");
                return true;
            }

            String craftItem = craftTarget(text);
            if (craftItem != null) {
                reply(craftReply(craftAuto(Map.of("item", craftItem, "count", 1)), craftItem));
                return true;
            }

            if (words.contains("come") || words.contains("comer")) {
                if (player.getFoodData().getFoodLevel() >= 20) {
                    reply("no tengo hambre ahora");
                } else {
                    int slot = findFoodSlot(player);
                    if (slot < 0) {
                        reply("no tengo comida");
                    } else {
                        eat(Map.of("slot", slot));
                        reply("dale, como algo");
                    }
                }
                return true;
            }

            if (words.contains("planta") || words.contains("replanta")
                    || words.contains("plantar")) {
                return plantReflex(player);
            }

            if (words.contains("mirame") || words.contains("mira") || words.contains("mirar")) {
                ServerPlayer target = player.server.getPlayerList().getPlayerByName(sender);
                if (target != null) {
                    lookAtPoint(player, target.getEyePosition());
                }
                reply("te miro");
                return true;
            }

            if (words.contains("ataca") || words.contains("atacalo") || words.contains("matalo")
                    || words.contains("defendete") || words.contains("defiendete")) {
                if (text.contains("no lo ataques") || text.contains("no la ataques")
                        || text.contains("no ataques") || text.contains("no lo mates")) {
                    return true;
                }
                return attackReflex(player);
            }

            if (words.contains("veni") || words.contains("ven") || words.contains("venite")
                    || words.contains("venis") || words.contains("venir") || words.contains("vengan")
                    || words.contains("seguime") || words.contains("segui")
                    || words.contains("seguir") || words.contains("follow")
                    || words.contains("acompaname") || words.contains("acompana")) {
                follow(Map.of("player", sender, "distance", 3.0));
                reply("dale, te sigo");
                return true;
            }

            if (words.contains("hola") || words.contains("buenas") || words.contains("hey")
                    || words.contains("ey") || words.contains("epa") || words.contains("holi")
                    || text.contains("buen dia") || text.contains("buenos dias")) {
                ServerPlayer greeted = player.server.getPlayerList().getPlayerByName(sender);
                if (greeted != null) {
                    lookAtPoint(player, greeted.getEyePosition());
                }
                this.greetTicks = 20;
                reply(greeting(sender));
                return true;
            }
            if (words.contains("gracias") || words.contains("thanks")) {
                this.greetTicks = 10;
                reply("de nada!");
                return true;
            }
        } catch (RuntimeException e) {
            reply("no pude: " + e.getMessage());
            return true;
        }
        return false;
    }

    /**
     * Recognises a stop order written the way people actually type in chat.
     * "para" is tricky because it is also a preposition, so a bare one only
     * counts when it stands alone or ends the sentence ("pero para", "che para").
     */
    private static boolean isStopCommand(String text, Set<String> words) {
        if (words.contains("parate") || words.contains("pare") || words.contains("paren")
                || words.contains("pararon") || words.contains("detente") || words.contains("detenete")
                || words.contains("detengan") || words.contains("stop") || words.contains("stopea")
                || words.contains("frena") || words.contains("frenen") || words.contains("frenate")
                || words.contains("quedate") || words.contains("quedese") || words.contains("quedes")
                || words.contains("quieto") || words.contains("quieta") || words.contains("quietos")
                || words.contains("quietas") || words.contains("espera") || words.contains("esperen")
                || words.contains("esperate") || words.contains("aguanta") || words.contains("aguanten")
                || words.contains("basta") || words.contains("cortala") || words.contains("aborta")
                || words.contains("cancela") || text.contains("no te muevas") || text.contains("no te movas")
                || text.contains("no sigas") || text.contains("no avances") || text.contains("no camines")
                || text.contains("deja de")) {
            return true;
        }
        // A bare "para" (also "pará", accents are stripped) at the end: "pero para", "che para".
        String[] tokens = text.split("\\s+");
        if (tokens.length > 0 && "para".equals(tokens[tokens.length - 1])) {
            return true;
        }
        if ("para".equals(text) || text.startsWith("para para") || text.startsWith("para ya")
                || text.startsWith("para ahi") || text.startsWith("para un toque")
                || text.startsWith("para un segundo") || text.startsWith("para un cachito")
                || text.startsWith("para loco") || text.startsWith("para boludo")
                || text.startsWith("para maestro") || text.startsWith("para capo")) {
            return true;
        }
        // "alto ahí" is an order; a plain "alto" alone too, but not "salto alto".
        return "alto".equals(text) || text.startsWith("alto ahi") || text.contains("alto ahi");
    }

    /** Bed-time orders, in both rioplatense and neutral Spanish. */
    private static boolean isSleepCommand(String text, Set<String> words) {
        return text.contains("a dormir") || text.contains("a la cama")
                || words.contains("dormi") || words.contains("dormite") || words.contains("dormir")
                || words.contains("duerme") || words.contains("duerman") || words.contains("durmio")
                || words.contains("acostate") || words.contains("acuestate") || words.contains("acuesta")
                || words.contains("acostar") || words.contains("acostarse") || words.contains("siesta")
                || words.contains("descansa") || words.contains("descansar");
    }

    /**
     * Finds the closest bed, reports if it is not bedtime, and otherwise starts a
     * {@link SleepTask} that walks there and lies down.
     */
    private boolean sleepReflex(CompanionPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos bed = findNearestBed(player, 48.0D);
        if (bed == null) {
            reply("no encuentro ninguna cama cerca");
            return true;
        }
        if (!isNight(level) && !level.isThundering()
                && "minecraft:overworld".equals(level.dimension().location().toString())) {
            reply("todavia no es de noche, no puedo dormir");
            return true;
        }
        String id = newAction("sleep", "running");
        startTask(new SleepTask(id, 48.0D), player);
        reply("dale, me voy a dormir a la cama mas cercana");
        return true;
    }

    /** Nearest bed block in a box around the companion, or null. */
    private BlockPos findNearestBed(CompanionPlayer player, double radius) {
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        int r = (int) Math.ceil(radius);
        BlockPos best = null;
        double bestDistance = radius * radius;
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-r, -6, -r), origin.offset(r, 6, r))) {
            if (!level.getBlockState(pos).is(BlockTags.BEDS)) {
                continue;
            }
            double distance = pos.distSqr(origin);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        return best;
    }

    /** Right-clicks a bed exactly like {@code use_item} would, with or without items in hand. */
    private void useBed(CompanionPlayer player, ServerLevel level, BlockPos pos) {
        Vec3 point = Vec3.atCenterOf(pos);
        requireBlockReach(player, point);
        lookAtPoint(player, point);
        BlockHitResult hit = new BlockHitResult(point, Direction.UP, pos, false);
        InteractionHand hand = InteractionHand.MAIN_HAND;
        player.gameMode.useItemOn(player, level, player.getItemInHand(hand), hand, hit);
        player.swing(hand, true);
        player.containerMenu.broadcastChanges();
    }

    // ------------------------------------------------------------------
    // Place memory by chat: "guarda este punto como BASE"
    // ------------------------------------------------------------------

    /**
     * True for orders that ask to memorise the current spot: "guarda este
     * punto", "guarda esto", "guardalo como base", "memoriza este lugar".
     */
    private static boolean isSavePlaceOrder(String text) {
        if (text.contains("no guardes") || text.contains("deja de guardar")) {
            return false;
        }
        boolean explicitSave = text.contains("guarda") || text.contains("guardalo")
                || text.contains("guardala");
        boolean otherSave = text.contains("memoriza") || text.contains("anota")
                || text.contains("recuerda");
        if (!explicitSave && !otherSave) {
            return false;
        }
        boolean placeWord = text.contains("punto") || text.contains("lugar")
                || text.contains("esto") || text.contains("aca") || text.contains("aqui")
                || text.contains("posicion");
        return placeWord || (explicitSave && (text.contains("base") || text.contains("como")));
    }

    /** Pulls a short place name out of the order ("... como BASE" -> "base"). */
    private static String placeNameFrom(String text) {
        String name = null;
        int marker = text.indexOf(" como ");
        if (marker >= 0) {
            name = text.substring(marker + 6).trim();
        } else if (text.startsWith("como ")) {
            name = text.substring(5).trim();
        }
        if (name == null || name.isBlank()) {
            if (text.contains("casa")) {
                name = "casa";
            } else if (text.contains("mina")) {
                name = "mina";
            } else if (text.contains("base")) {
                name = "base";
            } else {
                name = "punto";
            }
        }
        name = name.replaceAll("[^a-z0-9 _-]", "").trim();
        name = name.replaceAll("^(el|la|los|las|mi|mis|nuestro|nuestra|un|una)\\s+", "");
        if (name.length() > 24) {
            name = name.substring(0, 24).trim();
        }
        return name.isBlank() ? "punto" : name;
    }

    /**
     * Saves the speaker's current position (falls back to the companion's) so
     * it survives restarts. Same name updates the old spot.
     */
    private boolean savePlaceReflex(CompanionPlayer player, String sender, String text) {
        String name = placeNameFrom(text);
        ServerPlayer owner = player.server.getPlayerList().getPlayerByName(sender);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("note", "guardado por chat, pedido de " + sender);
        if (owner != null) {
            params.put("x", owner.getX());
            params.put("y", owner.getY());
            params.put("z", owner.getZ());
        }
        try {
            Map<String, Object> saved = remember(params);
            List<?> at = (List<?>) saved.get("at");
            reply("listo, guarde " + name + " en " + at.get(0) + ", " + at.get(1) + ", " + at.get(2));
        } catch (RuntimeException e) {
            reply("no pude guardarlo: " + e.getMessage());
        }
        return true;
    }

    /** "donde esta la base?" -> answers with a remembered place and how far it is. */
    private boolean recallPlaceReflex(CompanionPlayer player, String text) {
        if (!text.contains("donde")) {
            return false;
        }
        WorldMemory.Place found = null;
        for (WorldMemory.Place place : memory().all()) {
            String key = WorldMemory.key(place.name());
            if (key.length() < 3 || !text.contains(key)) {
                continue;
            }
            if (found != null) {
                return false; // ambiguous: let the model or the player clarify
            }
            found = place;
        }
        if (found == null) {
            return false;
        }
        double distance = Math.sqrt(player.distanceToSqr(found.x(), found.y(), found.z()));
        reply(found.name() + " queda en " + round(found.x()) + ", " + round(found.y()) + ", "
                + round(found.z()) + " (" + found.dim() + "), a " + round(distance) + " bloques");
        return true;
    }



    /** Sets the temperament (cauteloso / valiente / normal) and/or the lively idle behaviour. */
    private Map<String, Object> setMode(Map<String, Object> params) {
        String mode = str(params, "mode");
        if (mode != null && !mode.isBlank()) {
            setStance(mode);
        }
        if (params.containsKey("lively")) {
            this.lively = bool(params, "lively", true);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("mode", this.stance.name());
        out.put("lively", this.lively);
        out.put("enemy_range", this.stance.enemyRange());
        out.put("low_hp", (double) this.stance.lowHp());
        out.put("eat_below", this.stance.eatBelow());
        out.put("autopilot", autopilotState());
        return out;
    }

    /** cauteloso turns auto-defend off, valiente turns it on, normal leaves it as it is. */
    private void setStance(String raw) {
        switch (normalizeCommand(raw)) {
            case "cauteloso", "cautious", "careful" -> {
                this.stance = STANCE_CAUTIOUS;
                this.autoDefend = false;
            }
            case "valiente", "brave", "bold" -> {
                this.stance = STANCE_BRAVE;
                this.autoDefend = true;
            }
            case "normal", "default" -> this.stance = STANCE_NORMAL;
            default -> throw new IllegalArgumentException("mode must be cauteloso, valiente or normal");
        }
    }

    private boolean lavaNear(CompanionPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos base = player.blockPosition();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 1; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (level.getBlockState(base.offset(dx, dy, dz)).is(Blocks.LAVA)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private ServerPlayer nearestHuman(CompanionPlayer player, double radius) {
        ServerPlayer best = null;
        double bestDistance = radius * radius;
        for (ServerPlayer other : player.server.getPlayerList().getPlayers()) {
            if (other == player || other instanceof CompanionPlayer || other.level() != player.level()) {
                continue;
            }
            double distance = other.distanceToSqr(player);
            if (distance < bestDistance) {
                best = other;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** Turns the head toward whoever just spoke, when idle and within 24 blocks. */
    private void faceSpeaker(CompanionPlayer player, String sender) {
        if (!this.lively || this.activeTask != null || sender == null) {
            return;
        }
        ServerPlayer target = player.server.getPlayerList().getPlayerByName(sender);
        if (target != null && target != player && player.distanceToSqr(target) <= 24.0D * 24.0D) {
            lookAtPoint(player, target.getEyePosition());
        }
    }

    /** Greeting bow (two quick crouches) and an occasional glance at a nearby player while idle. */
    private void livelyTick(CompanionPlayer player) {
        if (this.greetTicks > 0) {
            this.greetTicks--;
            boolean down = this.greetTicks > 0 && ((this.greetTicks - 1) / 5) % 2 == 1;
            this.companion.controller().setSneak(down || this.sneakHeld);
            return;
        }
        if (this.activeTask != null || this.pendingGiveItem != null || this.autopilotTicks % 40L != 0L) {
            return;
        }
        ServerPlayer near = nearestHuman(player, 6.0D);
        if (near != null) {
            lookAtPoint(player, near.getEyePosition());
        }
    }

    private String greeting(String sender) {
        String[] options = {"hola " + sender + "!", "epa, " + sender, "buenas, " + sender + "!"};
        return options[(int) (this.autopilotTicks % options.length)];
    }

    /** Spanish phrase -> inventory id substrings, best first. null = unknown item. */
    private static String[] giveNeedles(Set<String> words, String text) {
        if (text.contains("comida") || text.contains("de comer")) return new String[] {"@food"};
        if (text.contains("madera") || text.contains("tronco")) return new String[] {"_log", "planks"};
        if (text.contains("tabla")) return new String[] {"planks"};
        if (text.contains("palo")) return new String[] {"stick"};
        if (text.contains("piedra") || text.contains("adoquin")) return new String[] {"cobblestone", "stone"};
        if (text.contains("arena") || words.contains("sand")) return new String[] {"_sand"};
        if (text.contains("grava") || text.contains("gravel")) return new String[] {"gravel"};
        if (text.contains("tierra") || text.contains("dirt")) return new String[] {"dirt"};
        if (text.contains("arcilla") || text.contains("clay")) return new String[] {"clay"};
        if (text.contains("vidrio") || text.contains("glass")) return new String[] {"glass"};
        if (text.contains("hierro")) return new String[] {"iron_ingot", "raw_iron", "iron"};
        if (text.contains("carbon")) return new String[] {"coal"};
        if (text.contains("diamante")) return new String[] {"diamond"};
        if (words.contains("oro")) return new String[] {"gold_ingot", "raw_gold", "gold"};
        if (text.contains("pico")) return new String[] {"pickaxe"};
        if (text.contains("hacha")) return new String[] {"_axe"};
        if (text.contains("espada")) return new String[] {"sword"};
        if (words.contains("pala")) return new String[] {"shovel"};
        if (text.contains("antorcha")) return new String[] {"torch"};
        if (words.contains("pan")) return new String[] {"bread"};
        if (text.contains("manzana")) return new String[] {"apple"};
        if (text.contains("cofre")) return new String[] {"chest"};
        if (text.contains("mesa")) return new String[] {"crafting_table"};
        if (words.contains("arco")) return new String[] {"bow"};
        return null;
    }

    private static int findInventorySlot(ServerPlayer player, String needle) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()
                    && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    /** Chat order "dame madera" / "pasame 5 palos": walk to the speaker and hand the item over. */
    private boolean giveReflex(CompanionPlayer player, String sender, Set<String> words, String text) {
        // One hand-over at a time: repeating "tirame arena" must not stack
        // tasks, replies or drops.
        if (this.pendingGiveItem != null || this.autopilotTicks - this.lastGiveAt < 60L) {
            return true;
        }
        String[] needles = giveNeedles(words, text);
        if (needles == null) {
            reply("que queres que te de?");
            return true;
        }
        ServerPlayer target = player.server.getPlayerList().getPlayerByName(sender);
        if (target == null) {
            reply("no te veo");
            return true;
        }
        int slot = -1;
        if ("@food".equals(needles[0])) {
            slot = findFoodSlot(player);
        } else {
            for (String needle : needles) {
                slot = findInventorySlot(player, needle);
                if (slot >= 0) {
                    break;
                }
            }
        }
        if (slot < 0) {
            reply("no tengo eso");
            return true;
        }
        int count = 0;
        for (String word : text.split("\\s+")) {
            if (word.matches("\\d{1,3}")) {
                count = Integer.parseInt(word);
                break;
            }
        }
        if ("going".equals(startGive(player, target, slot, count))) {
            reply("voy, ya te lo doy");
        }
        return true;
    }

    /** Hands over right away when close, otherwise walks to the player first. Returns gave | going. */
    private String startGive(CompanionPlayer player, ServerPlayer target, int slot, int count) {
        ItemStack stack = player.getInventory().getItem(slot);
        this.pendingGiveItem = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        this.pendingGiveCount = count;
        this.pendingGivePlayer = target.getGameProfile().getName();
        this.pendingGiveDeadline = this.autopilotTicks + 400L;
        if (player.distanceToSqr(target) <= 3.5D * 3.5D) {
            performGive(player, target);
            return "gave";
        }
        startTask(new GotoTask(newAction("goto", "running"),
                target.getX(), target.getY(), target.getZ(), 2.0D), player);
        return "going";
    }

    private void performGive(CompanionPlayer player, ServerPlayer target) {
        String item = this.pendingGiveItem;
        int count = this.pendingGiveCount;
        clearGive();
        this.lastGiveAt = this.autopilotTicks;
        cancelActive("give");
        this.companion.controller().stop();
        lookAtPoint(player, target.getEyePosition());
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("item", item);
            if (count > 0) {
                params.put("count", count);
            }
            dropItem(params);
            reply("toma, para vos");
        } catch (RuntimeException e) {
            reply("no pude dartelo: " + e.getMessage());
        }
    }

    private void pendingGiveTick(CompanionPlayer player) {
        if (this.pendingGiveItem == null) {
            return;
        }
        ServerPlayer target = this.pendingGivePlayer == null ? null
                : player.server.getPlayerList().getPlayerByName(this.pendingGivePlayer);
        if (target == null || this.autopilotTicks > this.pendingGiveDeadline) {
            clearGive();
            reply("no llego a darte eso");
            return;
        }
        if (player.distanceToSqr(target) <= 3.5D * 3.5D) {
            performGive(player, target);
        } else if (this.activeTask == null && this.autopilotTicks % 20L == 0L) {
            startTask(new GotoTask(newAction("goto", "running"),
                    target.getX(), target.getY(), target.getZ(), 2.0D), player);
        }
    }

    private void clearGive() {
        this.pendingGiveItem = null;
        this.pendingGivePlayer = null;
        this.pendingGiveCount = 0;
    }

    /** Tool version of the hand-over: {item, player?, count?}. */
    private Map<String, Object> give(Map<String, Object> params) {
        CompanionPlayer player = requireControl();
        String item = str(params, "item");
        if (item == null || item.isBlank()) {
            throw new IllegalArgumentException("item is required");
        }
        String who = str(params, "player");
        ServerPlayer target = who != null && !who.isBlank()
                ? player.server.getPlayerList().getPlayerByName(who)
                : nearestHuman(player, 64.0D);
        if (target == null) {
            throw new IllegalArgumentException("player not found");
        }
        String needle = item.toLowerCase(Locale.ROOT).trim();
        int slot = "food".equals(needle) ? findFoodSlot(player) : findInventorySlot(player, needle);
        if (slot < 0) {
            throw new IllegalArgumentException("no matching item in the inventory");
        }
        int count = (int) clamp(num(params, "count", 0.0D), 0.0D, 2304.0D);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", startGive(player, target, slot, count));
        out.put("ok", true);
        out.put("to", target.getGameProfile().getName());
        return out;
    }

    private static String craftTarget(String text) {
        if (!(text.contains("craftea") || text.contains("craftear")
                || text.contains("crafteame") || text.contains("craft"))) {
            return null;
        }
        String material = text.contains("hierro") || text.contains("iron") ? "iron_"
                : text.contains("piedra") || text.contains("stone") ? "stone_"
                : text.contains("diamante") || text.contains("diamond") ? "diamond_"
                : text.contains("oro") || text.contains("gold") ? "golden_"
                : "wooden_";
        if (text.contains("pico")) return material + "pickaxe";
        if (text.contains("hacha")) return material + "axe";
        if (text.contains("espada")) return material + "sword";
        if (text.contains("pala")) return material + "shovel";
        if (text.contains("azada") || text.contains("hoe")) return material + "hoe";
        if (text.contains("mesa")) return "crafting_table";
        if (text.contains("palo")) return "stick";
        if (text.contains("tabla")) return "planks";
        if (text.contains("antorcha")) return "torch";
        if (text.contains("cofre")) return "chest";
        if (text.contains("horno")) return "furnace";
        if (text.contains("silex")) return "flint_and_steel";
        return null;
    }

    private boolean plantReflex(CompanionPlayer player) {
        int slot = findBlockItemSlot(player, "sapling");
        if (slot < 0) {
            reply("no tengo saplings");
            return true;
        }
        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();
        BlockState here = level.getBlockState(pos);
        BlockState below = level.getBlockState(pos.below());
        if (!here.canBeReplaced()) {
            reply("no hay lugar para plantar aca");
            return true;
        }
        if (!isPlantableGround(below)) {
            reply("necesito tierra o pasto para plantar");
            return true;
        }
        ItemStack stack = player.getInventory().getItem(slot);
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        placeBlock(Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ(), "block", id));
        reply("planto un sapling");
        return true;
    }

    private static boolean isPlantableGround(BlockState state) {
        return state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.PODZOL) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.MUD)
                || state.is(Blocks.MUDDY_MANGROVE_ROOTS) || state.is(Blocks.FARMLAND);
    }

    private boolean attackReflex(CompanionPlayer player) {
        Entity target = nearestMonster(player, 8.0D);
        if (target == null) {
            reply("no veo bichos cerca");
            return true;
        }
        double reach = player.entityInteractionRange() + 0.5D;
        if (Math.sqrt(player.distanceToSqr(target)) <= reach) {
            lookAtPoint(player, target.getEyePosition());
            player.attackWithFullStrength(target);
            player.swing(InteractionHand.MAIN_HAND, true);
            reply("toma!");
        } else {
            startTask(new GotoTask(newAction("goto", "running"),
                    target.getX(), target.getY(), target.getZ(), 1.5D), player);
            reply("voy a por el");
        }
        return true;
    }

    private void maybeAutoEat(CompanionPlayer player) {
        if (player.getFoodData().getFoodLevel() > this.stance.eatBelow() || !player.canEat(false)) {
            return;
        }
        int slot = findFoodSlot(player);
        if (slot < 0) {
            return;
        }
        try {
            eat(Map.of("slot", slot));
        } catch (RuntimeException ignored) {
            // Full hunger or a transient issue: retry later.
        }
    }

    private void maybeAutoDefend(CompanionPlayer player) {
        if (player.getHealth() <= this.stance.defendMinHp()) {
            return;
        }
        Entity target = nearestMonster(player, player.entityInteractionRange());
        if (target == null) {
            return;
        }
        lookAtPoint(player, target.getEyePosition());
        player.attackWithFullStrength(target);
        player.swing(InteractionHand.MAIN_HAND, true);
    }

    private static Entity nearestMonster(ServerPlayer player, double radius) {
        Entity best = null;
        double bestDistance = radius * radius;
        for (Entity entity : player.serverLevel().getEntities(player,
                player.getBoundingBox().inflate(radius))) {
            if (!(entity instanceof net.minecraft.world.entity.monster.Monster) || !entity.isAlive()) {
                continue;
            }
            double distance = player.distanceToSqr(entity);
            if (distance < bestDistance) {
                best = entity;
                bestDistance = distance;
            }
        }
        return best;
    }

    private void reply(String message) {
        CompanionPlayer player = this.companion.player();
        if (player == null) {
            return;
        }
        MinecraftServer server = player.server;
        String name = player.getGameProfile().getName();
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("<" + name + "> " + message), false);
        this.chatLog.add(name, message, "chat", true);
        this.events.add("chat", Map.of("sender", name, "message", message));
    }

    private static String normalizeCommand(String raw) {
        String text = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return text.toLowerCase(Locale.ROOT).trim();
    }

    /**
     * Cheap intent heuristic: is this a question? Questions must not fire
     * movement orders by accident ("¿para qué sirve esto?").
     */
    private static boolean looksLikeQuestion(String text) {
        if (text.contains("?")) {
            return true;
        }
        String[] starts = {"que ", "como ", "cuando ", "donde ", "adonde ",
                "por que", "para que", "cual", "quien", "cuanto", "sirve"};
        for (String start : starts) {
            if (text.equals(start.trim()) || text.startsWith(start)) {
                return true;
            }
        }
        return false;
    }

    /** A question that is really a polite order ("¿me das arena?"). */
    private static boolean isRequestQuestion(String text) {
        return text.contains("podes") || text.contains("podrias") || text.contains("puedes")
                || text.contains("me das") || text.contains("me pasas")
                || text.contains("me tiras") || text.contains("me dropeas")
                || text.contains("me sueltas") || text.contains("me ayudas")
                || text.contains("me seguis") || text.contains("me acompanas")
                || text.contains("me defendes") || text.contains("me cuidas")
                || text.contains("venis") || text.contains("quieres que");
    }

    // ------------------------------------------------------------------
    // Task implementations
    // ------------------------------------------------------------------

    private interface Task {
        boolean tick(CompanionPlayer player, MinecraftServer server);

        /** Extra fields merged into the action status when the task ends. */
        default Map<String, Object> result(CompanionPlayer player) {
            return Map.of();
        }

        /** Called once when the task ends for any reason (done, failed, cancelled). */
        default void cleanup(CompanionPlayer player) {
        }
    }

    private void startTask(Task task, CompanionPlayer player) {
        cancelActive("replaced");
        this.activeTask = task;
        this.interruptOn = false;
        this.interruptExtra = null;
    }

    /**
     * Watches real distance-to-target progress instead of relying on
     * {@code horizontalCollision}, which stays false when the companion pushes
     * into blocks such as leaves. Jumps periodically while blocked and fails
     * the action when no progress is made at all.
     */
    private final class ProgressWatch {
        private static final double MIN_PROGRESS = 0.05D;
        private static final int JUMP_EVERY = 15;
        private static final int FAIL_AFTER = 200;

        private double best = Double.MAX_VALUE;
        private int idleTicks;

        void update(double distance) {
            if (distance < this.best - MIN_PROGRESS) {
                this.best = distance;
                this.idleTicks = 0;
                return;
            }
            this.idleTicks++;
            if (this.idleTicks % JUMP_EVERY == 0) {
                BridgeApi.this.companion.controller().jump();
            }
            if (this.idleTicks > FAIL_AFTER) {
                throw new IllegalStateException(
                        "no progress towards target for " + this.idleTicks + " ticks");
            }
        }

        int idleTicks() {
            return this.idleTicks;
        }

        void reset(double distance) {
            this.best = distance;
            this.idleTicks = 0;
        }
    }

    /**
     * Steers towards a point using the vanilla movement inputs. When the body
     * collides with something it sidesteps and jumps instead of just pushing
     * forward, which gets the companion around trunks and one-block steps
     * without real pathfinding.
     */
    private void driveTowards(CompanionPlayer player, double targetX, double targetY,
                              double targetZ, double tolerance, ProgressWatch watch) {
        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal <= tolerance) {
            return;
        }
        double dy = targetY - player.getY();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(horizontal, 0.01D)));
        CompanionController controller = BridgeApi.this.companion.controller();
        controller.look(yaw, (float) clamp(pitch, -45.0D, 45.0D));

        double strafe = 0.0D;
        if (player.horizontalCollision) {
            // Alternate the detour side so it does not get stuck hugging a wall.
            strafe = (watch.idleTicks() / 10) % 2 == 0 ? 0.7D : -0.7D;
            if (player.onGround()) {
                controller.jump();
            }
        }
        controller.move(1.0D, strafe, horizontal > 5.0D, 3);
        watch.update(horizontal);
    }

    private final class MoveTask implements Task {
        private final String id;
        private final double forward;
        private final double strafe;
        private final boolean sprint;
        private int remaining;

        private double startX;
        private double startZ;
        private boolean started;

        MoveTask(String id, double forward, double strafe, boolean sprint, int ticks) {
            this.id = id;
            this.forward = forward;
            this.strafe = strafe;
            this.sprint = sprint;
            this.remaining = ticks;
        }

        @Override
        public boolean tick(CompanionPlayer player, MinecraftServer server) {
            if (!this.started) {
                this.startX = player.getX();
                this.startZ = player.getZ();
                this.started = true;
            }
            if (this.remaining <= 0) {
                return true;
            }
            BridgeApi.this.companion.controller()
                    .move(this.forward, this.strafe, this.sprint, this.remaining);
            this.remaining--;
            return this.remaining <= 0;
        }

        double travelled(CompanionPlayer player) {
            if (!this.started) {
                return 0.0D;
            }
            double dx = player.getX() - this.startX;
            double dz = player.getZ() - this.startZ;
            return Math.sqrt(dx * dx + dz * dz);
        }
    }

    private enum PathStatus { RUNNING, ARRIVED, STUCK }

    /**
     * Walks a path produced by {@link Pathfinder} waypoint by waypoint using the
     * normal movement inputs: jumps for step-ups, swims when in water, sprints
     * on straight flat stretches and reports STUCK so the owner can replan.
     */
    private final class PathWalker {
        private final List<BlockPos> path;
        private int index = 1;
        private int shownIndex = -1;
        private double best = Double.MAX_VALUE;
        private int idle;
        private int ticks;

        PathWalker(List<BlockPos> path) {
            this.path = path;
        }

        PathStatus tick(CompanionPlayer player) {
            CompanionController controller = BridgeApi.this.companion.controller();
            this.ticks++;
            while (this.index < this.path.size()) {
                BlockPos wp = this.path.get(this.index);
                double dx = wp.getX() + 0.5D - player.getX();
                double dz = wp.getZ() + 0.5D - player.getZ();
                double dy = wp.getY() - player.getY();
                boolean last = this.index == this.path.size() - 1;
                if (Math.sqrt(dx * dx + dz * dz) < (last ? 0.4D : 0.6D) && dy > -1.3D && dy < 1.3D) {
                    this.index++;
                } else {
                    break;
                }
            }
            if (this.index >= this.path.size()) {
                controller.stop();
                return PathStatus.ARRIVED;
            }
            if (this.ticks > this.path.size() * 25 + 100) {
                return PathStatus.STUCK;
            }

            BlockPos wp = this.path.get(this.index);
            double dx = wp.getX() + 0.5D - player.getX();
            double dz = wp.getZ() + 0.5D - player.getZ();
            double dy = wp.getY() - player.getY();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            if (dy < -4.0D) {
                // Fell off the route.
                return PathStatus.STUCK;
            }
            if (this.shownIndex != this.index) {
                this.shownIndex = this.index;
                this.best = Double.MAX_VALUE;
                this.idle = 0;
            }
            if (horizontal < this.best - 0.03D) {
                this.best = horizontal;
                this.idle = 0;
            } else {
                this.idle++;
            }
            if (this.idle > 40) {
                return PathStatus.STUCK;
            }

            controller.look((float) Math.toDegrees(Math.atan2(-dx, dz)), 0.0F);
            if (player.onGround() && dy > 0.6D && horizontal < 1.3D) {
                controller.jump();
            } else if (player.isInWater() && dy > -0.5D) {
                controller.jump();
            } else if (this.idle > 0 && this.idle % 12 == 0 && player.onGround()) {
                controller.jump();
            }
            controller.move(1.0D, 0.0D, sprintAhead(), 3);
            return PathStatus.RUNNING;
        }

        /** Sprint only on a straight, flat run of at least three waypoints (no overshooting turns or edges). */
        private boolean sprintAhead() {
            int i = this.index;
            if (i + 2 >= this.path.size()) {
                return false;
            }
            BlockPos a = this.path.get(i);
            BlockPos b = this.path.get(i + 1);
            BlockPos c = this.path.get(i + 2);
            return a.getY() == b.getY() && b.getY() == c.getY()
                    && b.getX() - a.getX() == c.getX() - b.getX()
                    && b.getZ() - a.getZ() == c.getZ() - b.getZ();
        }
    }

    private final class SleepTask implements Task {
        private final String id;
        private final double radius;
        private final ProgressWatch progress = new ProgressWatch();
        private BlockPos bed;
        private PathWalker walker;
        private boolean used;
        private int ticks;
        private int replans;
        private int waitTicks;

        SleepTask(String id, double radius) {
            this.id = id;
            this.radius = radius;
        }

        @Override
        public boolean tick(CompanionPlayer player, MinecraftServer server) {
            ServerLevel level = player.serverLevel();
            if (++this.ticks > 2400) {
                throw new IllegalStateException("could not reach a bed in time");
            }
            if (player.isSleeping()) {
                return true;
            }
            if (this.bed == null || !level.getBlockState(this.bed).is(BlockTags.BEDS)) {
                this.bed = findNearestBed(player, this.radius);
                if (this.bed == null) {
                    throw new IllegalStateException("no bed found within " + (int) this.radius + " blocks");
                }
                this.walker = null;
                this.used = false;
                this.waitTicks = 0;
            }

            double reach = player.blockInteractionRange() - 0.3D;
            if (player.getEyePosition().distanceTo(Vec3.atCenterOf(this.bed)) <= reach) {
                this.walker = null;
                if (!this.used) {
                    useBed(player, level, this.bed);
                    this.used = true;
                    this.waitTicks = 0;
                } else if (++this.waitTicks > 40) {
                    throw new IllegalStateException("the bed is occupied or it is not bedtime");
                }
                return false;
            }
            if (this.used) {
                // Tried from afar and it did not take: move closer and retry.
                this.used = false;
            }

            if (this.walker == null) {
                if (!plan(player)) {
                    this.replans++;
                    if (this.replans > 6) {
                        driveTowards(player, this.bed.getX() + 0.5D, this.bed.getY(),
                                this.bed.getZ() + 0.5D, 1.8D, this.progress);
                    }
                    return false;
                }
            }
            if (this.walker != null) {
                if (this.walker.tick(player) != PathStatus.RUNNING) {
                    this.walker = null;
                    this.replans++;
                }
            }
            return false;
        }

        /** Plans a path to a standable spot next to the bed; partial paths are fine. */
        private boolean plan(CompanionPlayer player) {
            Pathfinder.Result result = Pathfinder.find(player.serverLevel(), player.blockPosition(),
                    pos -> {
                        double dx = pos.getX() + 0.5D - (this.bed.getX() + 0.5D);
                        double dz = pos.getZ() + 0.5D - (this.bed.getZ() + 0.5D);
                        return Math.sqrt(dx * dx + dz * dz) <= 2.4D
                                && Math.abs(pos.getY() - this.bed.getY()) <= 2.0D;
                    },
                    Vec3.atCenterOf(this.bed), 3000);
            if (result.path().size() < 2) {
                return false;
            }
            this.walker = new PathWalker(result.path());
            return true;
        }

        @Override
        public Map<String, Object> result(CompanionPlayer player) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("sleeping", player.isSleeping());
            if (this.bed != null) {
                out.put("bed", List.of(this.bed.getX(), this.bed.getY(), this.bed.getZ()));
            }
            return out;
        }
    }

    private final class GotoTask implements Task {
        private final String id;
        private final double x;
        private final double y;
        private final double z;
        private final double tolerance;
        private final ProgressWatch progress = new ProgressWatch();
        private PathWalker walker;
        private boolean legacy;
        private int replans;
        private double lastPlanDistance = Double.MAX_VALUE;

        GotoTask(String id, double x, double y, double z, double tolerance) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
            this.tolerance = tolerance;
        }

        @Override
        public boolean tick(CompanionPlayer player, MinecraftServer server) {
            double dx = this.x - player.getX();
            double dz = this.z - player.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance <= this.tolerance) {
                return true;
            }
            if (!this.legacy) {
                if (this.walker == null && !plan(player, distance)) {
                    // No usable path: fall back to straight-line driving.
                    this.legacy = true;
                }
                if (this.walker != null) {
                    PathStatus status = this.walker.tick(player);
                    if (status != PathStatus.RUNNING) {
                        this.walker = null;
                    }
                    return false;
                }
            }
            double targetY = Double.isNaN(this.y) ? player.getY() : this.y;
            driveTowards(player, this.x, targetY, this.z, this.tolerance, this.progress);
            return false;
        }

        /** Plans (or replans) a path; partial paths are fine, we replan when they end. */
        private boolean plan(CompanionPlayer player, double distance) {
            if (distance > this.lastPlanDistance - 2.0D) {
                this.replans++;
            }
            this.lastPlanDistance = distance;
            if (this.replans > 6) {
                throw new IllegalStateException("no_path: could not get closer to the target");
            }
            double slack = Math.max(this.tolerance, 0.9D);
            boolean useY = !Double.isNaN(this.y);
            Vec3 goalPoint = new Vec3(this.x, useY ? this.y : player.getY(), this.z);
            Pathfinder.Result result = Pathfinder.find(player.serverLevel(), player.blockPosition(),
                    pos -> {
                        double gx = pos.getX() + 0.5D - this.x;
                        double gz = pos.getZ() + 0.5D - this.z;
                        return Math.sqrt(gx * gx + gz * gz) <= slack
                                && (!useY || Math.abs(pos.getY() - this.y) <= 2.0D);
                    },
                    goalPoint, 6000);
            if (result.path().size() < 2) {
                return false;
            }
            this.walker = new PathWalker(result.path());
            return true;
        }

        @Override
        public Map<String, Object> result(CompanionPlayer player) {
            double dx = this.x - player.getX();
            double dz = this.z - player.getZ();
            return Map.of("remaining", round1(Math.sqrt(dx * dx + dz * dz)));
        }
    }

    private final class FollowTask implements Task {
        private final String id;
        private final String playerName;
        private final double distance;
        private final ProgressWatch progress = new ProgressWatch();
        private PathWalker walker;
        private Vec3 planEnd = Vec3.ZERO;
        private int cooldown;

        FollowTask(String id, String playerName, double distance) {
            this.id = id;
            this.playerName = playerName;
            this.distance = distance;
        }

        @Override
        public boolean tick(CompanionPlayer player, MinecraftServer server) {
            ServerPlayer target = server.getPlayerList().getPlayerByName(this.playerName);
            if (target == null) {
                throw new IllegalStateException("player not online: " + this.playerName);
            }
            if (target == player) {
                return true;
            }
            double dx = target.getX() - player.getX();
            double dz = target.getZ() - player.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance <= this.distance) {
                BridgeApi.this.companion.controller().stop();
                this.walker = null;
                this.progress.reset(distance);
                return false;
            }
            double dy = target.getY() - player.getY();
            if (this.cooldown > 0) {
                this.cooldown--;
            }
            if (distance < 6.0D && Math.abs(dy) < 1.2D) {
                // Close and level: walking straight is enough.
                this.walker = null;
            } else {
                if (this.walker != null && this.cooldown == 0
                        && target.position().distanceToSqr(this.planEnd) > 16.0D) {
                    this.walker = null;
                }
                if (this.walker == null && this.cooldown == 0) {
                    this.cooldown = 20;
                    double slack = Math.max(this.distance, 1.5D);
                    Pathfinder.Result result = Pathfinder.find(player.serverLevel(), player.blockPosition(),
                            pos -> {
                                double gx = pos.getX() + 0.5D - target.getX();
                                double gz = pos.getZ() + 0.5D - target.getZ();
                                return Math.sqrt(gx * gx + gz * gz) <= slack
                                        && Math.abs(pos.getY() - target.getY()) <= 2.0D;
                            },
                            target.position(), 3000);
                    if (result.path().size() >= 2) {
                        this.walker = new PathWalker(result.path());
                        this.planEnd = target.position();
                    }
                }
                if (this.walker != null) {
                    if (this.walker.tick(player) != PathStatus.RUNNING) {
                        this.walker = null;
                    }
                    this.progress.reset(distance);
                    return false;
                }
            }
            driveTowards(player, target.getX(), target.getY(), target.getZ(),
                    this.distance, this.progress);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private MinecraftServer requireServer() {
        MinecraftServer server = this.companion.server();
        if (server == null || !server.isRunning()) {
            throw new IllegalArgumentException(
                    "no server is running; load a singleplayer world first");
        }
        return server;
    }

    private CompanionPlayer requirePlayer() {
        CompanionPlayer player = this.companion.player();
        if (player == null) {
            throw new IllegalArgumentException("companion is not present");
        }
        return player;
    }

    private CompanionPlayer requireControl() {
        if (this.emergency) {
            throw new IllegalArgumentException("emergency stop is active; call reset_emergency_stop");
        }
        return requirePlayer();
    }

    private String newAction(String method, String state) {
        String id = "act-" + (this.nextActionId++);
        Map<String, Object> map = new ConcurrentHashMap<>();
        map.put("id", id);
        map.put("method", method);
        map.put("state", state);
        this.actions.put(id, map);
        if (this.actions.size() > 128) {
            String oldest = this.actions.keySet().iterator().next();
            this.actions.remove(oldest);
        }
        return id;
    }

    private void finishTask(String state, String reason) {
        Task task = this.activeTask;
        this.activeTask = null;
        Map<String, Object> interruptInfo = this.interruptExtra;
        this.interruptExtra = null;
        this.interruptOn = false;
        if (task != null) {
            String id = null;
            if (task instanceof MoveTask move) id = move.id;
            else if (task instanceof GotoTask gotoTask) id = gotoTask.id;
            else if (task instanceof FollowTask followTask) id = followTask.id;
            else if (task instanceof GatherTask gatherTask) id = gatherTask.id;
            else if (task instanceof CraftTask craftTask) id = craftTask.id;
            Map<String, Object> result = null;
            if (id != null) {
                Map<String, Object> action = this.actions.get(id);
                if (action != null) {
                    action.put("state", state);
                    if (interruptInfo != null) {
                        action.putAll(interruptInfo);
                    }
                    if (reason != null) {
                        action.put("reason", reason);
                    }
                    CompanionPlayer player = this.companion.player();
                    if (player != null) {
                        action.put("at", List.of(round1(player.getX()), round1(player.getY()), round1(player.getZ())));
                        task.cleanup(player);
                        result = task.result(player);
                        for (Map.Entry<String, Object> extra : result.entrySet()) {
                            if (extra.getValue() != null) {
                                action.put(extra.getKey(), extra.getValue());
                            }
                        }
                    }
                    if (player != null && task instanceof MoveTask move) {
                        action.put("travelled", round(move.travelled(player)));
                    }
                }
            }
            if ("completed".equals(state) && result != null) {
                announceCompletion(task, result);
            }
        }
        this.companion.controller().stop();
        if (task != null && !(task instanceof FollowTask)
                && !"chat_stop".equals(reason) && !"replaced".equals(reason)
                && this.followTarget != null) {
            // The follow order stays valid after chores, giving items or a
            // finished combat chase. Only "para" cancels it.
            this.followResume = true;
            this.followResumeAt = this.autopilotTicks + 20L;
        }
    }

    private boolean cancelActive(String reason) {
        if (this.activeTask == null) {
            return false;
        }
        finishTask("cancelled", reason);
        return true;
    }

    /** One short line when a long chore really finished (never on cancel/fail). */
    private void announceCompletion(Task task, Map<String, Object> result) {
        if (task instanceof GatherTask) {
            Object gained = result.get("gained");
            String detail = "";
            if (gained instanceof Map<?, ?> map && !map.isEmpty()) {
                StringBuilder text = new StringBuilder(": ");
                int count = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (count++ > 0) {
                        text.append(", ");
                    }
                    text.append(entry.getValue()).append(" ").append(entry.getKey());
                    if (count >= 3) {
                        break;
                    }
                }
                detail = text.toString();
            }
            reply("listo, termine de recolectar" + detail);
        } else if (task instanceof CraftTask) {
            reply("listo, termine de craftear");
        }
    }

    private Map<String, Object> actionResult(String id, String state) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("action_id", id);
        map.put("state", state);
        return map;
    }

    private Map<String, Object> entityInfo(Entity entity, ServerPlayer viewer) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("uuid", entity.getUUID().toString());
        map.put("name", entity.getName().getString());
        map.put("type", shortName(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()));
        map.put("x", round(entity.getX()));
        map.put("y", round(entity.getY()));
        map.put("z", round(entity.getZ()));
        map.put("distance", round(Math.sqrt(entity.distanceToSqr(viewer))));
        if (entity instanceof LivingEntity living) {
            map.put("health", round(living.getHealth()));
            map.put("max_health", round(living.getMaxHealth()));
        }
        if (entity instanceof ServerPlayer player) {
            map.put("player", true);
            map.put("name", player.getGameProfile().getName());
        }
        return map;
    }

    private Map<String, Object> stackInfo(int slot, ItemStack stack) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("slot", slot);
        if (stack == null || stack.isEmpty()) {
            map.put("empty", true);
            return map;
        }
        ResourceLocation item = BuiltInRegistries.ITEM.getKey(stack.getItem());
        map.put("item", shortName(item.toString()));
        map.put("count", stack.getCount());
        if (stack.getDamageValue() > 0) {
            map.put("damage", stack.getDamageValue());
        }
        return map;
    }

    private static String handName(InteractionHand hand) {
        return hand == InteractionHand.OFF_HAND ? "offhand" : "main";
    }

    /** Centre of the given face of a block. */
    private static Vec3 facePoint(BlockPos pos, Direction face) {
        return Vec3.atCenterOf(pos).add(
                face.getStepX() * 0.5D,
                face.getStepY() * 0.5D,
                face.getStepZ() * 0.5D);
    }

    private static void requireBlockReach(ServerPlayer player, Vec3 point) {
        double max = player.blockInteractionRange() + 0.5D;
        double distance = Math.sqrt(player.distanceToSqr(point));
        if (distance > max) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "block is %.2f blocks away; walk closer (max %.2f)", distance, max));
        }
    }

    /** Points the companion's body and head at a world position. */
    private static void lookAtPoint(ServerPlayer player, Vec3 point) {
        double dx = point.x - player.getX();
        double dy = point.y - player.getEyeY();
        double dz = point.z - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(pitch);
    }

    private static Direction direction(Map<String, Object> params, Direction fallback) {
        String raw = str(params, "face");
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Direction parsed = Direction.byName(raw.trim().toLowerCase(Locale.ROOT));
        if (parsed == null) {
            throw new IllegalArgumentException("unknown face: " + raw
                    + " (use up, down, north, south, east or west)");
        }
        return parsed;
    }

    private static Entity findEntity(ServerPlayer player, String query, double radius) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("entity is required");
        }
        String needle = query.toLowerCase(Locale.ROOT);
        AABB box = player.getBoundingBox().inflate(radius);
        for (Entity entity : player.serverLevel().getEntities(player, box, e -> e != player)) {
            String name = entity.getName().getString().toLowerCase(Locale.ROOT);
            String display = entity.getDisplayName().getString().toLowerCase(Locale.ROOT);
            if (entity.getUUID().toString().equalsIgnoreCase(query)
                    || name.equals(needle)
                    || display.equals(needle)
                    || name.contains(needle)) {
                return entity;
            }
        }
        throw new IllegalArgumentException("entity not found within "
                + (int) radius + " blocks: " + query);
    }

    private static int findBlockItemSlot(ServerPlayer player, String wanted) {
        Inventory inventory = player.getInventory();
        String needle = wanted == null ? null : wanted.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }
            String id = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString();
            if (needle == null || id.equals(needle) || id.contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static int findFoodSlot(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getFoodProperties(player) != null) {
                return i;
            }
        }
        return -1;
    }

    private static double num(Map<String, Object> params, String key, double fallback) {
        Object value = params.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static int intParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            return Integer.parseInt(text);
        }
        throw new IllegalArgumentException(key + " is required");
    }

    private static boolean bool(Map<String, Object> params, String key, boolean fallback) {
        Object value = params.get(key);
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return fallback;
    }

    private static String str(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object entry : list) {
                result.add(String.valueOf(entry));
            }
            return result;
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text);
        }
        return List.of();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0D) / 10.0D;
    }

    /** Drops the "minecraft:" prefix; inputs match by substring so short ids still work. */
    private static String shortName(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }
}
