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
import net.minecraft.world.Container;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
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
            Block cropBlock = state.getBlock();
            String name = cropBlock.getName().getString();
            level.destroyBlock(immutable, true, player);
            harvested++;
            if (names.size() < 6) {
                names.add(name);
            }
            // Replant the SAME crop that was just harvested, not always wheat.
            if (replant && replantAt(player, level, immutable, cropBlock)) {
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
            if (plantAnySeed(player, level, above)) {
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

    /**
     * Replants the exact crop that was harvested, consuming its seed. Carrots
     * and potatoes are planted with the vegetable itself, which is what vanilla
     * does.
     */
    private static boolean replantAt(ServerPlayer player, ServerLevel level, BlockPos pos,
                                     Block cropBlock) {
        Item seed;
        BlockState planted;
        if (cropBlock == Blocks.WHEAT) {
            seed = Items.WHEAT_SEEDS;
            planted = Blocks.WHEAT.defaultBlockState();
        } else if (cropBlock == Blocks.CARROTS) {
            seed = Items.CARROT;
            planted = Blocks.CARROTS.defaultBlockState();
        } else if (cropBlock == Blocks.POTATOES) {
            seed = Items.POTATO;
            planted = Blocks.POTATOES.defaultBlockState();
        } else if (cropBlock == Blocks.BEETROOTS) {
            seed = Items.BEETROOT_SEEDS;
            planted = Blocks.BEETROOTS.defaultBlockState();
        } else if (cropBlock == Blocks.NETHER_WART) {
            // Nether wart goes back on soul sand, not farmland.
            if (!level.getBlockState(pos.below()).is(Blocks.SOUL_SAND)) {
                return false;
            }
            seed = Items.NETHER_WART;
            planted = Blocks.NETHER_WART.defaultBlockState();
        } else {
            return false;
        }
        if (!take(player, seed, 1)) {
            return false;
        }
        level.setBlockAndUpdate(pos, planted);
        return true;
    }

    /**
     * Plants whichever seed the player actually carries, in a fixed priority
     * order. Returns false when the inventory holds no plantable seed.
     */
    private static boolean plantAnySeed(ServerPlayer player, ServerLevel level, BlockPos pos) {
        if (take(player, Items.WHEAT_SEEDS, 1)) {
            level.setBlockAndUpdate(pos, Blocks.WHEAT.defaultBlockState());
            return true;
        }
        if (take(player, Items.CARROT, 1)) {
            level.setBlockAndUpdate(pos, Blocks.CARROTS.defaultBlockState());
            return true;
        }
        if (take(player, Items.POTATO, 1)) {
            level.setBlockAndUpdate(pos, Blocks.POTATOES.defaultBlockState());
            return true;
        }
        if (take(player, Items.BEETROOT_SEEDS, 1)) {
            level.setBlockAndUpdate(pos, Blocks.BEETROOTS.defaultBlockState());
            return true;
        }
        return false;
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

    /**
     * Summarises the contents of nearby containers so the companion can decide
     * what is already in stock before gathering more.
     */
    public static Map<String, Object> auditChests(ServerPlayer player, int radius) {
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        Map<String, Integer> totals = new LinkedHashMap<>();
        int containers = 0;
        List<String> scanned = new ArrayList<>();

        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -2, -radius),
                origin.offset(radius, 2, radius))) {
            if (containers >= 24) {
                break;
            }
            if (!(level.getBlockEntity(pos) instanceof Container container)) {
                continue;
            }
            // Skip the companion's own handled screens: only world blocks count.
            BlockState state = level.getBlockState(pos);
            containers++;
            if (scanned.size() < 12) {
                scanned.add(state.getBlock().getName().getString()
                        + " @ " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
            }
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                String key = stack.getHoverName().getString();
                totals.merge(key, stack.getCount(), Integer::sum);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("containers", containers);
        out.put("scanned", scanned);
        out.put("totals", new LinkedHashMap<String, Object>(totals));
        return out;
    }
}
