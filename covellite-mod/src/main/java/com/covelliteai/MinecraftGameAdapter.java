package com.covelliteai;

import covellite.core.GameAdapter;
import covellite.core.action.Action;
import covellite.core.action.ActionQueue;
import covellite.core.action.ActionStatus;
import covellite.core.action.Step;
import covellite.core.event.Event;
import covellite.core.event.EventLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Real {@link GameAdapter} implementation. Every method must be called on the
 * Minecraft client thread; the mod's tick handler and dispatcher guarantee that.
 */
public final class MinecraftGameAdapter implements GameAdapter {
    private static final int MAX_NEARBY_BLOCKS = 256;

    private final Minecraft mc = Minecraft.getInstance();
    private final ActionQueue actions;
    private final EventLog events;
    private final String expectedPlayerName;

    // Movement state written by actions on the client thread and read by the
    // movement-input event during the same thread's player tick.
    private volatile boolean driving;
    private volatile float forward;
    private volatile float strafe;
    private volatile boolean sprint;
    private volatile boolean jump;
    private volatile boolean sneak;

    public MinecraftGameAdapter(ActionQueue actions, EventLog events, String expectedPlayerName) {
        this.actions = actions;
        this.events = events;
        this.expectedPlayerName = expectedPlayerName == null ? "" : expectedPlayerName;
    }

    public boolean isAiPlayer(LocalPlayer player) {
        if (player == null) return false;
        if (expectedPlayerName.isBlank()) return true;
        return expectedPlayerName.equalsIgnoreCase(player.getGameProfile().getName());
    }

    private LocalPlayer player() {
        LocalPlayer p = mc.player;
        if (p == null) throw new IllegalStateException("not_connected");
        return p;
    }

    private ClientLevel level() {
        ClientLevel l = mc.level;
        if (l == null) throw new IllegalStateException("no_world");
        return l;
    }

    // ------------------------------------------------------------------
    // Perception
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> getPlayerState() {
        LocalPlayer p = player();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.getGameProfile().getName());
        m.put("uuid", p.getUUID().toString());
        m.put("dimension", dimensionName(level()));
        m.put("x", p.getX());
        m.put("y", p.getY());
        m.put("z", p.getZ());
        m.put("block_x", p.blockPosition().getX());
        m.put("block_y", p.blockPosition().getY());
        m.put("block_z", p.blockPosition().getZ());
        m.put("yaw", p.getYRot());
        m.put("pitch", p.getXRot());
        m.put("velocity", vec(p.getDeltaMovement()));
        m.put("on_ground", p.onGround());
        m.put("sneaking", p.isShiftKeyDown());
        m.put("sprinting", p.isSprinting());
        m.put("health", p.getHealth());
        m.put("max_health", p.getMaxHealth());
        m.put("food", p.getFoodData().getFoodLevel());
        m.put("saturation", p.getFoodData().getSaturationLevel());
        m.put("air", p.getAirSupply());
        m.put("max_air", p.getMaxAirSupply());
        m.put("xp_level", p.experienceLevel);
        m.put("in_water", p.isInWater());
        m.put("in_lava", p.isInLava());
        m.put("dead", p.isDeadOrDying());
        m.put("selected_slot", p.getInventory().selected);
        if (mc.gameMode != null) m.put("game_mode", mc.gameMode.getPlayerMode().getName());
        return m;
    }

    @Override
    public Map<String, Object> getWorldInfo() {
        LocalPlayer p = player();
        ClientLevel l = level();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dimension", dimensionName(l));
        m.put("day_time", l.getDayTime());
        m.put("game_time", l.getGameTime());
        m.put("difficulty", l.getDifficulty().name().toLowerCase(Locale.ROOT));
        m.put("is_day", l.isDay());
        m.put("raining", l.isRaining());
        m.put("thundering", l.isThundering());
        m.put("biome", l.getBiome(p.blockPosition()).unwrapKey()
                .map(k -> k.location().toString()).orElse("unknown"));
        return m;
    }

    @Override
    public List<Map<String, Object>> getPlayerList() {
        List<Map<String, Object>> list = new ArrayList<>();
        if (mc.getConnection() == null) return list;
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", info.getProfile().getName());
            m.put("uuid", info.getProfile().getId().toString());
            m.put("latency", info.getLatency());
            m.put("game_mode", info.getGameMode().getName());
            list.add(m);
        }
        return list;
    }

    @Override
    public Map<String, Object> getInventory() {
        LocalPlayer p = player();
        Inventory inv = p.getInventory();
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) items.add(itemMap(stack, i));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("selected_slot", inv.selected);
        m.put("items", items);
        return m;
    }

    @Override
    public Map<String, Object> getEquipment() {
        LocalPlayer p = player();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mainhand", itemMap(p.getItemBySlot(EquipmentSlot.MAINHAND), -1));
        m.put("offhand", itemMap(p.getItemBySlot(EquipmentSlot.OFFHAND), -1));
        m.put("head", itemMap(p.getItemBySlot(EquipmentSlot.HEAD), -1));
        m.put("chest", itemMap(p.getItemBySlot(EquipmentSlot.CHEST), -1));
        m.put("legs", itemMap(p.getItemBySlot(EquipmentSlot.LEGS), -1));
        m.put("feet", itemMap(p.getItemBySlot(EquipmentSlot.FEET), -1));
        return m;
    }

    public Map<String, Object> getHotbar() {
        LocalPlayer p = player();
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            items.add(itemMap(p.getInventory().getItem(i), i));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("selected_slot", p.getInventory().selected);
        m.put("items", items);
        return m;
    }

    @Override
    public List<Map<String, Object>> getNearbyEntities(double radius, int limit) {
        LocalPlayer p = player();
        double r = clamp(radius, 1.0, 64.0);
        int max = clampInt(limit, 32, 1, 128);
        AABB box = p.getBoundingBox().inflate(r);
        List<Entity> entities = level().getEntities(p, box, e -> e != p);
        entities.sort((a, b) -> Double.compare(p.distanceToSqr(a), p.distanceToSqr(b)));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Entity e : entities) {
            if (list.size() >= max) break;
            list.add(entityMap(e));
        }
        return list;
    }

    @Override
    public List<Map<String, Object>> getNearbyBlocks(double radius, List<String> filters, int limit) {
        LocalPlayer p = player();
        ClientLevel l = level();
        double r = clamp(radius, 1.0, 16.0);
        int max = clampInt(limit, 64, 1, MAX_NEARBY_BLOCKS);
        BlockPos center = p.blockPosition();
        int ir = (int) Math.ceil(r);
        List<String> wanted = new ArrayList<>();
        if (filters != null) {
            for (String f : filters) if (f != null && !f.isBlank()) wanted.add(f.toLowerCase(Locale.ROOT));
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-ir, -ir, -ir), center.offset(ir, ir, ir))) {
            if (list.size() >= max) break;
            if (p.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > r * r) continue;
            BlockState state = l.getBlockState(pos);
            if (state.isAir()) continue;
            String id = blockId(state);
            if (!wanted.isEmpty() && wanted.stream().noneMatch(w -> id.contains(w))) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("block", id);
            m.put("x", pos.getX());
            m.put("y", pos.getY());
            m.put("z", pos.getZ());
            m.put("distance", Math.sqrt(p.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)));
            list.add(m);
        }
        return list;
    }

    @Override
    public Map<String, Object> getTargetedObject() {
        HitResult hit = mc.hitResult;
        Map<String, Object> m = new LinkedHashMap<>();
        if (hit == null) {
            m.put("type", "none");
            return m;
        }
        if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult bhr) {
            BlockPos pos = bhr.getBlockPos();
            m.put("type", "block");
            m.put("block", blockId(level().getBlockState(pos)));
            m.put("x", pos.getX());
            m.put("y", pos.getY());
            m.put("z", pos.getZ());
            m.put("face", bhr.getDirection().getName());
            m.put("hit", vec(bhr.getLocation()));
            return m;
        }
        if (hit.getType() == HitResult.Type.ENTITY && hit instanceof EntityHitResult ehr) {
            m.put("type", "entity");
            m.put("entity", entityMap(ehr.getEntity()));
            return m;
        }
        m.put("type", hit.getType().name().toLowerCase(Locale.ROOT));
        return m;
    }

    @Override
    public Map<String, Object> getEntityInfo(String uuidOrName) {
        if (uuidOrName == null || uuidOrName.isBlank()) throw new IllegalArgumentException("missing entity id or name");
        LocalPlayer p = player();
        AABB box = p.getBoundingBox().inflate(64.0);
        for (Entity e : level().getEntities(p, box, x -> x != p)) {
            if (e.getUUID().toString().equalsIgnoreCase(uuidOrName)
                    || e.getName().getString().equalsIgnoreCase(uuidOrName)) {
                return entityMap(e);
            }
        }
        throw new IllegalArgumentException("entity_not_found");
    }

    @Override
    public Map<String, Object> getBlockInfo(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        ClientLevel l = level();
        if (!l.isLoaded(pos)) throw new IllegalArgumentException("chunk_not_loaded");
        BlockState state = l.getBlockState(pos);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("block", blockId(state));
        m.put("name", state.getBlock().getName().getString());
        m.put("x", x);
        m.put("y", y);
        m.put("z", z);
        m.put("air", state.isAir());
        m.put("hardness", state.getDestroySpeed(l, pos));
        m.put("properties", state.toString());
        return m;
    }

    @Override
    public Map<String, Object> getChatHistory(int limit) {
        int max = clampInt(limit, 20, 1, 256);
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Event e : events.recent(max)) {
            if (!"chat".equals(e.type())) continue;
            Map<String, Object> m = new LinkedHashMap<>(e.payload());
            m.put("timestamp", e.timestamp());
            messages.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("messages", messages);
        return result;
    }

    @Override
    public List<Map<String, Object>> getRecentEvents(long since, int limit) {
        int max = clampInt(limit, 32, 1, 256);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Event e : events.since(since, max)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", e.type());
            m.put("sequence", e.sequence());
            m.put("timestamp", e.timestamp());
            m.put("data", e.payload());
            list.add(m);
        }
        return list;
    }

    // ------------------------------------------------------------------
    // Movement / control
    // ------------------------------------------------------------------

    @Override
    public String move(double forward, double strafe, boolean sprint, int ticks) {
        requireAiPlayer();
        int duration = clampInt(ticks, 1, 1, 200);
        float f = (float) clamp(forward, -1.0, 1.0);
        float s = (float) clamp(strafe, -1.0, 1.0);
        ActionStatus status = submit(new Action() {
            private int elapsed;

            @Override public String type() { return "move"; }
            @Override public Step start() { setMovement(f, s, sprint); return Step.CONTINUE; }
            @Override public Step tick() {
                if (++elapsed >= duration) return Step.DONE;
                setMovement(f, s, sprint);
                return Step.CONTINUE;
            }
            @Override public void stop() { clearMovement(); }
        }, Math.max(1000L, duration * 50L + 1000L));
        return status.id();
    }

    @Override
    public String look(double yaw, double pitch) {
        requireAiPlayer();
        float y = (float) wrapDegrees(yaw);
        float p = (float) clamp(pitch, -90.0, 90.0);
        ActionStatus status = submit(new Action() {
            @Override public String type() { return "look"; }
            @Override public Step start() { setRotation(y, p); return Step.DONE; }
            @Override public Step tick() { return Step.DONE; }
        }, 2000L);
        return status.id();
    }

    @Override
    public String lookAt(double x, double y, double z) {
        requireAiPlayer();
        Vec3 eye = player().getEyePosition();
        double dx = x - eye.x;
        double dy = y - eye.y;
        double dz = z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        return look(yaw, pitch);
    }

    @Override
    public String jump() {
        requireAiPlayer();
        ActionStatus status = submit(new Action() {
            private int elapsed;

            @Override public String type() { return "jump"; }
            @Override public Step start() { jump = true; return Step.CONTINUE; }
            @Override public Step tick() { return ++elapsed >= 2 ? Step.DONE : Step.CONTINUE; }
            @Override public void stop() { jump = false; }
        }, 3000L);
        return status.id();
    }

    @Override
    public String setSneak(boolean enabled) {
        requireAiPlayer();
        sneak = enabled;
        ActionStatus status = submit(new Action() {
            @Override public String type() { return "set_sneak"; }
            @Override public Step start() { return Step.DONE; }
            @Override public Step tick() { return Step.DONE; }
        }, 1000L);
        return status.id();
    }

    @Override
    public String stop() {
        actions.cancelAll();
        clearMovement();
        return "stopped";
    }

    @Override
    public String follow(String playerName, double distance) {
        requireAiPlayer();
        if (playerName == null || playerName.isBlank()) throw new IllegalArgumentException("missing player name");
        double desired = clamp(distance, 1.0, 16.0);
        ActionStatus status = submit(new Action() {
            private int elapsed;

            @Override public String type() { return "follow"; }
            @Override public Step start() { return Step.CONTINUE; }
            @Override public Step tick() {
                if (++elapsed > 1200) return Step.DONE;
                LocalPlayer self = player();
                AbstractClientPlayer target = null;
                for (var other : level().players()) {
                    if (other != self && other.getGameProfile().getName().equalsIgnoreCase(playerName)) {
                        target = other;
                        break;
                    }
                }
                if (target == null) return Step.FAIL;
                double dist = Math.sqrt(self.distanceToSqr(target));
                if (dist <= desired) {
                    clearMovement();
                    face(target.getX(), target.getEyeY(), target.getZ());
                    return Step.CONTINUE;
                }
                face(target.getX(), target.getEyeY(), target.getZ());
                setMovement(1.0f, 0.0f, dist > desired + 3.0);
                if (self.horizontalCollision || (target.getY() - self.getY() > 1.0 && self.onGround())) jump = true;
                return Step.CONTINUE;
            }
            @Override public void stop() { clearMovement(); }
        }, 90_000L);
        return status.id();
    }

    @Override
    public String goTo(double x, double y, double z, double tolerance) {
        requireAiPlayer();
        double tol = clamp(tolerance, 0.5, 8.0);
        ActionStatus status = submit(new Action() {
            private int elapsed;

            @Override public String type() { return "goto"; }
            @Override public Step start() { return Step.CONTINUE; }
            @Override public Step tick() {
                if (++elapsed > 1200) return Step.DONE;
                LocalPlayer self = player();
                double dx = x - self.getX();
                double dz = z - self.getZ();
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                if (horizontal <= tol && Math.abs(y - self.getY()) <= 1.5) {
                    clearMovement();
                    return Step.DONE;
                }
                face(x, y + 1.0, z);
                setMovement(1.0f, 0.0f, horizontal > 6.0);
                if (self.horizontalCollision || (y - self.getY() > 1.0 && self.onGround())) jump = true;
                return Step.CONTINUE;
            }
            @Override public void stop() { clearMovement(); }
        }, 90_000L);
        return status.id();
    }

    @Override
    public String emergencyStop() {
        actions.emergencyStop();
        clearMovement();
        sneak = false;
        return "emergency_stop";
    }

    @Override
    public void sendChat(String message) {
        requireAiPlayer();
        if (message == null) throw new IllegalArgumentException("missing message");
        String text = message.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("empty message");
        if (text.length() > 256) throw new IllegalArgumentException("message too long");
        LocalPlayer p = player();
        if (text.startsWith("/")) {
            p.connection.sendCommand(text.substring(1));
        } else {
            p.connection.sendChat(text);
        }
    }

    @Override
    public Map<String, Object> getActionStatus(String id) {
        ActionStatus status = actions.status(id);
        if (status == null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("found", false);
            m.put("id", id);
            return m;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("found", true);
        m.put("id", status.id());
        m.put("type", status.type());
        m.put("state", status.state().name().toLowerCase(Locale.ROOT));
        m.put("created_at", status.createdAt());
        if (status.startedAt() != null) m.put("started_at", status.startedAt());
        if (status.finishedAt() != null) m.put("finished_at", status.finishedAt());
        if (status.message() != null) m.put("message", status.message());
        return m;
    }

    @Override
    public boolean cancelAction(String id) {
        return actions.cancel(id);
    }

    // ------------------------------------------------------------------
    // Tick integration
    // ------------------------------------------------------------------

    /** Applies the AI movement state over the keyboard input for this tick. */
    public void applyInput(Input input) {
        if (input == null) return;
        if (driving) {
            input.forwardImpulse = forward;
            input.leftImpulse = strafe;
            input.jumping = jump;
            if (sneak) input.shiftKeyDown = true;
            if (sprint) {
                LocalPlayer p = mc.player;
                if (p != null) p.setSprinting(true);
            }
        } else if (sneak) {
            input.shiftKeyDown = true;
        }
    }

    public boolean isDriving() {
        return driving;
    }

    public void stopImmediate() {
        driving = false;
        forward = 0;
        strafe = 0;
        sprint = false;
        jump = false;
    }

    private void setMovement(float f, float s, boolean sp) {
        forward = f;
        strafe = s;
        sprint = sp;
        driving = true;
        LocalPlayer p = mc.player;
        if (p != null) p.setSprinting(sp && f > 0 && !sneak);
    }

    private void clearMovement() {
        driving = false;
        forward = 0;
        strafe = 0;
        sprint = false;
        jump = false;
    }

    private void setRotation(float yaw, float pitch) {
        LocalPlayer p = player();
        p.setYRot(yaw);
        p.setXRot(pitch);
        p.setYHeadRot(yaw);
        p.yRotO = yaw;
        p.xRotO = pitch;
    }

    private void face(double x, double y, double z) {
        Vec3 eye = player().getEyePosition();
        double dx = x - eye.x;
        double dy = y - eye.y;
        double dz = z - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        setRotation(yaw, (float) clamp(pitch, -90.0, 90.0));
    }

    private ActionStatus submit(Action action, long timeoutMs) {
        try {
            return actions.submit(action, timeoutMs);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private void requireAiPlayer() {
        LocalPlayer p = player();
        if (!isAiPlayer(p)) {
            throw new IllegalStateException("local player is not the configured CoveMCP player");
        }
    }

    // ------------------------------------------------------------------
    // Mapping helpers
    // ------------------------------------------------------------------

    private Map<String, Object> entityMap(Entity e) {
        LocalPlayer self = mc.player;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uuid", e.getUUID().toString());
        m.put("name", e.getName().getString());
        m.put("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
        m.put("x", e.getX());
        m.put("y", e.getY());
        m.put("z", e.getZ());
        if (self != null) m.put("distance", Math.sqrt(self.distanceToSqr(e)));
        m.put("alive", e.isAlive());
        if (e instanceof LivingEntity living) {
            m.put("health", living.getHealth());
            m.put("max_health", living.getMaxHealth());
        }
        return m;
    }

    private static Map<String, Object> itemMap(ItemStack stack, int slot) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (stack == null || stack.isEmpty()) {
            m.put("empty", true);
            if (slot >= 0) m.put("slot", slot);
            return m;
        }
        m.put("empty", false);
        if (slot >= 0) m.put("slot", slot);
        m.put("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        m.put("name", stack.getHoverName().getString());
        m.put("count", stack.getCount());
        if (stack.isDamageableItem()) {
            m.put("damage", stack.getDamageValue());
            m.put("max_damage", stack.getMaxDamage());
        }
        return m;
    }

    private static Map<String, Object> vec(Vec3 v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", v.x);
        m.put("y", v.y);
        m.put("z", v.z);
        return m;
    }

    private static String blockId(BlockState state) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key == null ? "unknown" : key.toString();
    }

    private static String dimensionName(ClientLevel level) {
        return level.dimension().location().toString();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int fallback, int min, int max) {
        int v = value == 0 ? fallback : value;
        return Math.max(min, Math.min(max, v));
    }

    private static double wrapDegrees(double degrees) {
        double d = degrees % 360.0;
        if (d >= 180.0) d -= 360.0;
        if (d < -180.0) d += 360.0;
        return d;
    }
}
