package com.covelliteai.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.covelliteai.CovelliteAI;

import covellite.core.dispatch.Dispatcher;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

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
    private final Map<String, Map<String, Object>> actions = new ConcurrentHashMap<>();
    private long nextActionId = 1L;
    private Task activeTask;
    private boolean emergency;

    public BridgeApi(CompanionManager companion) {
        this.companion = companion;
    }

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

    public void register(Dispatcher dispatcher) {
        // Perception
        dispatcher.register("get_player_state", params -> playerState());
        dispatcher.register("get_world_info", params -> worldInfo());
        dispatcher.register("get_player_list", params -> playerList());
        dispatcher.register("get_inventory", params -> inventory());
        dispatcher.register("get_hotbar", params -> hotbar());
        dispatcher.register("get_equipment", params -> equipment());
        dispatcher.register("get_nearby_entities", this::nearbyEntities);
        dispatcher.register("get_nearby_blocks", this::nearbyBlocks);
        dispatcher.register("get_block_info", this::blockInfo);
        dispatcher.register("get_entity_info", this::entityInfo);
        dispatcher.register("get_targeted_object", params -> targetedObject());
        dispatcher.register("get_chat_history", params -> Map.of("messages", List.of()));
        dispatcher.register("get_recent_events", params -> Map.of("events", List.of()));

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
        if (this.activeTask == null) {
            return;
        }
        CompanionPlayer player = this.companion.player();
        if (player == null) {
            finishTask("cancelled", "companion_missing");
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
        map.put("name", player.getGameProfile().getName());
        map.put("uuid", player.getUUID().toString());
        map.put("dimension", player.serverLevel().dimension().location().toString());
        map.put("x", round(player.getX()));
        map.put("y", round(player.getY()));
        map.put("z", round(player.getZ()));
        map.put("yaw", round(player.getYRot()));
        map.put("pitch", round(player.getXRot()));
        map.put("health", round(player.getHealth()));
        map.put("max_health", round(player.getMaxHealth()));
        map.put("food", player.getFoodData().getFoodLevel());
        map.put("saturation", round(player.getFoodData().getSaturationLevel()));
        map.put("air", player.getAirSupply());
        map.put("game_mode", player.gameMode.getGameModeForPlayer().getName());
        map.put("on_ground", player.onGround());
        map.put("sneaking", player.isShiftKeyDown());
        map.put("sprinting", player.isSprinting());
        map.put("alive", player.isAlive());
        return map;
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

    private Map<String, Object> nearbyBlocks(Map<String, Object> params) {
        CompanionPlayer player = requirePlayer();
        int radius = (int) clamp(num(params, "radius", 8.0D), 1.0D, 16.0D);
        int limit = (int) clamp(num(params, "limit", 64.0D), 1.0D, 256.0D);
        List<String> filters = stringList(params.get("blocks"));
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        List<Map<String, Object>> result = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-radius, -radius, -radius),
                origin.offset(radius, radius, radius))) {
            if (result.size() >= limit) {
                break;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (!filters.isEmpty() && filters.stream().noneMatch(id::contains)) {
                continue;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("x", pos.getX());
            map.put("y", pos.getY());
            map.put("z", pos.getZ());
            map.put("block", id);
            map.put("distance", round(Math.sqrt(player.blockPosition().distSqr(pos))));
            result.add(map);
        }
        return Map.of("blocks", result, "count", result.size());
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
        String id = newAction("look", "completed");
        return actionResult(id, "completed");
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
        String id = newAction("look_at", "completed");
        return actionResult(id, "completed");
    }

    private Map<String, Object> jump(Map<String, Object> params) {
        requireControl();
        this.companion.controller().jump();
        String id = newAction("jump", "completed");
        return actionResult(id, "completed");
    }

    private Map<String, Object> setSneak(Map<String, Object> params) {
        requireControl();
        boolean enabled = bool(params, "enabled", false);
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
        double y = num(params, "y", player.getY());
        double z = num(params, "z", player.getZ());
        double tolerance = clamp(num(params, "tolerance", DEFAULT_TOLERANCE), 0.5D, 8.0D);
        String id = newAction("goto", "running");
        startTask(new GotoTask(id, x, y, z, tolerance), player);
        return actionResult(id, "running");
    }

    private Map<String, Object> follow(Map<String, Object> params) {
        CompanionPlayer player = requireControl();
        String name = str(params, "player");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("player is required");
        }
        double distance = clamp(num(params, "distance", 3.0D), 1.0D, 16.0D);
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
        if (message.startsWith("/")) {
            server.getCommands().performPrefixedCommand(
                    player.createCommandSourceStack(), message.substring(1));
            return Map.of("ok", true, "command", true);
        }
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("<" + player.getGameProfile().getName() + "> " + message), false);
        return Map.of("ok", true, "command", false);
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
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", spawned || this.companion.isPresent());
        map.put("spawned", spawned);
        map.put("companion", this.companion.state());
        return map;
    }

    // ------------------------------------------------------------------
    // Task implementations
    // ------------------------------------------------------------------

    private interface Task {
        boolean tick(CompanionPlayer player, MinecraftServer server);
    }

    private void startTask(Task task, CompanionPlayer player) {
        cancelActive("replaced");
        this.activeTask = task;
    }

    /**
     * Watches real distance-to-target progress instead of relying on
     * {@code horizontalCollision}, which stays false when the companion pushes
     * into blocks such as leaves. Jumps periodically while blocked and fails
     * the action when no progress is made at all.
     */
    private final class ProgressWatch {
        private static final double MIN_PROGRESS = 0.08D;
        private static final int JUMP_EVERY = 20;
        private static final int FAIL_AFTER = 120;

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

        void reset(double distance) {
            this.best = distance;
            this.idleTicks = 0;
        }
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

    private final class GotoTask implements Task {
        private final String id;
        private final double x;
        private final double y;
        private final double z;
        private final double tolerance;
        private final ProgressWatch progress = new ProgressWatch();

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
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            BridgeApi.this.companion.controller().look(yaw, 0.0F);
            BridgeApi.this.companion.controller().move(1.0D, 0.0D, distance > 6.0D, 3);
            this.progress.update(distance);
            return false;
        }
    }

    private final class FollowTask implements Task {
        private final String id;
        private final String playerName;
        private final double distance;
        private final ProgressWatch progress = new ProgressWatch();

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
                this.progress.reset(distance);
                return false;
            }
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            BridgeApi.this.companion.controller().look(yaw, 0.0F);
            BridgeApi.this.companion.controller().move(1.0D, 0.0D, distance > 6.0D, 3);
            this.progress.update(distance);
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
        if (task != null) {
            String id = null;
            if (task instanceof MoveTask move) id = move.id;
            else if (task instanceof GotoTask gotoTask) id = gotoTask.id;
            else if (task instanceof FollowTask followTask) id = followTask.id;
            if (id != null) {
                Map<String, Object> action = this.actions.get(id);
                if (action != null) {
                    action.put("state", state);
                    if (reason != null) {
                        action.put("reason", reason);
                    }
                    CompanionPlayer player = this.companion.player();
                    if (player != null && task instanceof MoveTask move) {
                        action.put("travelled", round(move.travelled(player)));
                    }
                }
            }
        }
        this.companion.controller().stop();
    }

    private boolean cancelActive(String reason) {
        if (this.activeTask == null) {
            return false;
        }
        finishTask("cancelled", reason);
        return true;
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
        map.put("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
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
        map.put("empty", false);
        map.put("item", item.toString());
        map.put("count", stack.getCount());
        map.put("damage", stack.getDamageValue());
        map.put("max_damage", stack.getMaxDamage());
        map.put("name", stack.getHoverName().getString());
        return map;
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
        return Math.round(value * 1000.0D) / 1000.0D;
    }
}
