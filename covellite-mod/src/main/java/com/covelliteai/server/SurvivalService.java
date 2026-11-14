package com.covelliteai.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Survival chores the companion can actually perform: smelting, farming and
 * animal breeding.
 *
 * <p>Every operation runs on the server thread, only touches blocks next to the
 * companion and consumes real items from their inventory. Nothing is simulated:
 * if the player has no seeds, planting fails and says so.</p>
 */
public final class SurvivalService {

    private SurvivalService() {
    }

    // ------------------------------------------------------------------
    // Furnaces
    // ------------------------------------------------------------------

    /** Nearest furnace, blast furnace or smoker within {@code radius}. */
    public static AbstractFurnaceBlockEntity nearestFurnace(ServerPlayer player, int radius) {
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        AbstractFurnaceBlockEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -2, -radius),
                origin.offset(radius, 2, radius))) {
            if (level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace) {
                double distance = pos.distSqr(origin);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = furnace;
                }
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("no furnace within " + radius + " blocks");
        }
        return best;
    }

    /** Contents of the three furnace slots. */
    public static Map<String, Object> furnaceStatus(ServerPlayer player, int radius) {
        AbstractFurnaceBlockEntity furnace = nearestFurnace(player, radius);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("input", describe(furnace.getItem(0)));
        out.put("fuel", describe(furnace.getItem(1)));
        out.put("output", describe(furnace.getItem(2)));
        out.put("lit", furnace.getBlockState().getValue(
                net.minecraft.world.level.block.AbstractFurnaceBlock.LIT));
        return out;
    }

    /**
     * Puts smeltables in the input slot and fuel in the fuel slot.
     *
     * @param itemQuery substring of the item id, e.g. {@code iron_ore}; empty
     *                  keeps whatever is already there
     * @param fuelQuery substring for the fuel; empty uses coal, planks or logs
     */
    public static Map<String, Object> furnaceLoad(ServerPlayer player, int radius,
                                                  String itemQuery, String fuelQuery, int count) {
        AbstractFurnaceBlockEntity furnace = nearestFurnace(player, radius);
        int moved = 0;
        if (itemQuery != null && !itemQuery.isBlank()) {
            moved += moveInto(player, furnace, 0, count, itemQuery, false);
        }
        int fuel = moveInto(player, furnace, 1, 64, fuelQuery == null ? "" : fuelQuery, true);
        furnace.setChanged();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("input", describe(furnace.getItem(0)));
        out.put("fuel", describe(furnace.getItem(1)));
        out.put("output", describe(furnace.getItem(2)));
        out.put("moved", moved);
        out.put("fuel_moved", fuel);
        return out;
    }

    /** Takes everything from the output slot into the inventory. */
    public static Map<String, Object> furnaceCollect(ServerPlayer player, int radius) {
        AbstractFurnaceBlockEntity furnace = nearestFurnace(player, radius);
        ItemStack output = furnace.getItem(2);
        Map<String, Object> out = new LinkedHashMap<>();
        if (output.isEmpty()) {
            out.put("collected", 0);
            out.put("items", List.of());
            return out;
        }
        int count = output.getCount();
        String name = output.getHoverName().getString();
        ItemStack remaining = output.copy();
        player.getInventory().add(remaining);
        furnace.setItem(2, remaining.isEmpty() ? ItemStack.EMPTY : remaining);
        furnace.setChanged();
        out.put("collected", count - remaining.getCount());
        out.put("items", List.of(name));
        return out;
    }

    /** Moves matching items from the inventory into a furnace slot. */
    private static int moveInto(ServerPlayer player, AbstractFurnaceBlockEntity furnace, int slot,
                                int limit, String query, boolean fuelDefault) {
        ItemStack existing = furnace.getItem(slot);
        int space = Math.max(0, limit - existing.getCount());
        if (space == 0) {
            return 0;
        }
        ItemStack target = existing.isEmpty() ? ItemStack.EMPTY : existing;
        int moved = 0;
        List<ItemStack> items = player.getInventory().items;
        for (int i = 0; i < items.size() && space > 0; i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty() || !accepts(stack, query, fuelDefault)) {
                continue;
            }
            if (!target.isEmpty() && !ItemStack.isSameItemSameComponents(target, stack)) {
                continue;
            }
            int take = Math.min(space, stack.getCount());
            if (target.isEmpty()) {
                target = stack.copyWithCount(take);
            } else {
                target.grow(take);
            }
            stack.shrink(take);
            if (stack.isEmpty()) {
                items.set(i, ItemStack.EMPTY);
            }
            space -= take;
            moved += take;
        }
        if (moved > 0) {
            furnace.setItem(slot, target);
        }
        return moved;
    }

    private static boolean accepts(ItemStack stack, String query, boolean fuelDefault) {
        if (query != null && !query.isBlank()) {
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            return id.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
        }
        if (!fuelDefault) {
            return false;
        }
        return stack.is(ItemTags.COALS) || stack.is(ItemTags.PLANKS)
                || stack.is(ItemTags.LOGS) || stack.is(Items.LAVA_BUCKET)
                || stack.is(Items.STICK) || stack.is(Items.BLAZE_ROD);
    }

    // ------------------------------------------------------------------
    // Farming
    // ------------------------------------------------------------------

    /** Harvests mature crops around the companion and optionally replants them. */
    public static Map<String, Object> harvest(ServerPlayer player, int radius, boolean replant) {
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        int harvested = 0;
        int replanted = 0;
        List<String> names = new ArrayList<>();

        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -2, -radius),
                origin.offset(radius, 2, radius))) {
            BlockPos immutable = pos.immutable();
            BlockState state = level.getBlockState(immutable);
            if (!isMature(state)) {
                continue;
            }
            String name = state.getBlock().getName().getString();
            level.destroyBlock(immutable, true, player);
            harvested++;
            if (names.size() < 6) {
                names.add(name);
            }
            if (replant && plantAt(player, level, immutable)) {
                replanted++;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("harvested", harvested);
        out.put("replanted", replanted);
        out.put("crops", names);
        return out;
    }

    /** Plants available seeds on nearby empty farmland. */
    public static Map<String, Object> plant(ServerPlayer player, int radius) {
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        int planted = 0;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -2, -radius),
                origin.offset(radius, 2, radius))) {
            BlockPos soil = pos.immutable();
            if (!(level.getBlockState(soil).getBlock() instanceof FarmBlock)) {
                continue;
            }
            BlockPos above = soil.above();
            if (!level.isEmptyBlock(above)) {
                continue;
            }
            if (plantAt(player, level, above)) {
                planted++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planted", planted);
        return out;
    }

    private static boolean isMature(BlockState state) {
        if (state.getBlock() instanceof CropBlock crop) {
            return crop.isMaxAge(state);
        }
        if (state.getBlock() instanceof NetherWartBlock) {
            return state.getValue(NetherWartBlock.AGE) >= 3;
        }
        return false;
    }

    /** Plants the matching seed when the player carries one. */
    private static boolean plantAt(ServerPlayer player, ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos.below());
        Item seed;
        BlockState planted;
        if (state.getBlock() == Blocks.WHEAT || state.getBlock() instanceof FarmBlock) {
            seed = Items.WHEAT_SEEDS;
            planted = Blocks.WHEAT.defaultBlockState();
        } else {
            return false;
        }
        if (state.getBlock() == Blocks.WHEAT) {
            return false;
        }
        if (!take(player, seed, 1)) {
            return false;
        }
        level.setBlockAndUpdate(pos, planted);
        return true;
    }

    // ------------------------------------------------------------------
    // Animals
    // ------------------------------------------------------------------

    /** Feeds nearby animals so they breed. Consumes real food items. */
    public static Map<String, Object> feedAnimals(ServerPlayer player, int radius) {
        AABB box = player.getBoundingBox().inflate(radius);
        List<Animal> animals = player.level().getEntitiesOfClass(Animal.class, box,
                animal -> animal.isAlive() && !animal.isBaby());
        int fed = 0;
        for (Animal animal : animals) {
            if (fed >= 6) {
                break;
            }
            ItemStack food = findFood(player, animal);
            if (food == null) {
                continue;
            }
            animal.setInLove(player);
            food.shrink(1);
            fed++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fed", fed);
        out.put("animals_nearby", animals.size());
        return out;
    }

    private static ItemStack findFood(ServerPlayer player, Animal animal) {
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty() && animal.isFood(stack)) {
                return stack;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Inventory helpers
    // ------------------------------------------------------------------

    private static boolean take(ServerPlayer player, Item item, int count) {
        int remaining = count;
        List<ItemStack> items = player.getInventory().items;
        for (int i = 0; i < items.size() && remaining > 0; i++) {
            ItemStack stack = items.get(i);
            if (!stack.is(item)) {
                continue;
            }
            int taken = Math.min(remaining, stack.getCount());
            stack.shrink(taken);
            if (stack.isEmpty()) {
                items.set(i, ItemStack.EMPTY);
            }
            remaining -= taken;
        }
        return remaining == 0;
    }

    private static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "vacio";
        }
        return stack.getHoverName().getString() + " x" + stack.getCount();
    }

    /** Summary used by status output. */
    public static Map<String, Object> describeInventory(ServerPlayer player) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("food", player.getFoodData().getFoodLevel());
        int seeds = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(Items.WHEAT_SEEDS)) {
                seeds += stack.getCount();
            }
        }
        map.put("wheat_seeds", seeds);
        return map;
    }

    /** True when the animal is a breeding candidate the player can feed. */
    public static boolean breedable(AgeableMob mob) {
        return mob.getAge() == 0 && !mob.isBaby();
    }
}
