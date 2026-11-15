package com.covelliteai.server;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.TridentItem;

/**
 * Combat loadout helpers: what to hold, when to block, when to shoot and when
 * to leave.
 *
 * <p>Everything here is a plain read/write of the companion's own inventory on
 * the server thread; no new physics and no invented damage. The tool handlers in
 * {@link BridgeApi} call these helpers so the MCP path and the in-process agent
 * behave identically.</p>
 */
public final class CombatKit {

    /** Health at or below this while outnumbered is a retreat. */
    private static final float RETREAT_HEALTH = 8.0F;
    private static final float RETREAT_OUTNUMBERED = 12.0F;

    private CombatKit() {
    }

    // ------------------------------------------------------------------
    // Weapons
    // ------------------------------------------------------------------

    /** Ranks the main-inventory weapons; higher is better, 0 means bare hands. */
    private static double weaponScore(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0.0D;
        }
        Item item = stack.getItem();
        double base;
        if (item instanceof TridentItem) {
            base = 5.0D;
        } else if (item instanceof SwordItem) {
            base = 4.0D;
        } else if (item instanceof AxeItem) {
            base = 3.0D;
        } else {
            return 0.0D;
        }
        double tier = item instanceof TieredItem tiered ? tiered.getTier().getAttackDamageBonus() : 0.0D;
        // A nearly broken weapon loses the duel against a fresh one.
        double durability = stack.isDamageableItem() && stack.getMaxDamage() > 0
                ? (double) (stack.getMaxDamage() - stack.getDamageValue()) / stack.getMaxDamage()
                : 1.0D;
        return base + tier + durability * 2.0D;
    }

    /** Finds the best weapon slot in the main inventory, or -1. */
    public static int bestWeaponSlot(ServerPlayer player) {
        int best = -1;
        double bestScore = 0.0D;
        List<ItemStack> items = player.getInventory().items;
        for (int i = 0; i < items.size(); i++) {
            double score = weaponScore(items.get(i));
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** Moves the best weapon into the main hand. Returns its display name. */
    public static String equipBestWeapon(ServerPlayer player) {
        int slot = bestWeaponSlot(player);
        if (slot < 0) {
            return "hands";
        }
        selectOrSwap(player, slot);
        return player.getMainHandItem().getHoverName().getString();
    }

    /** True when the main hand already holds the best available weapon. */
    public static boolean holdingBestWeapon(ServerPlayer player) {
        int slot = bestWeaponSlot(player);
        return slot < 0 || weaponScore(player.getMainHandItem()) >= weaponScore(player.getInventory().items.get(slot));
    }

    /** Spanish word -> fragment of the registry id it usually means. */
    private static final String[][] ALIASES = {
            {"espada", "sword"}, {"pico", "pickaxe"}, {"hacha", "axe"}, {"pala", "shovel"},
            {"azada", "hoe"}, {"arco", "bow"}, {"ballesta", "crossbow"}, {"escudo", "shield"},
            {"tridente", "trident"}, {"antorcha", "torch"}, {"flecha", "arrow"},
            {"piedra", "stone"}, {"adoquin", "cobblestone"}, {"tierra", "dirt"},
            {"arena", "sand"}, {"grava", "gravel"}, {"madera", "log"}, {"tronco", "log"},
            {"tabla", "planks"}, {"hierro", "iron"}, {"oro", "gold"}, {"diamante", "diamond"},
            {"carbon", "coal"}, {"esmeralda", "emerald"}, {"lapis", "lapis"},
            {"pan", "bread"}, {"manzana", "apple"}, {"carne", "beef"}, {"cerdo", "porkchop"},
            {"pollo", "chicken"}, {"pescado", "cod"}, {"balde", "bucket"}, {"cubo", "bucket"},
            {"agua", "water_bucket"}, {"cama", "bed"}, {"cofre", "chest"},
            {"mesa", "crafting_table"}, {"horno", "furnace"}, {"vidrio", "glass"},
            {"ladrillo", "brick"}, {"tela", "wool"}, {"linterna", "lantern"}
    };

    /**
     * Finds the inventory slot that best answers a plain-language request, such
     * as "la espada", "el pico de hierro", "piedra" or "antorcha".
     *
     * @return the slot index, or -1 when nothing in the bag matches
     */
    public static int findSlotFor(ServerPlayer player, String query) {
        String q = normalize(query);
        if (q.isEmpty()) {
            return -1;
        }
        int best = -1;
        double bestScore = 0.0D;
        List<ItemStack> items = player.getInventory().items;
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            double score = matchScore(stack, q);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** How well a stack answers the request; 0 means it does not match at all. */
    private static double matchScore(ItemStack stack, String q) {
        Item item = stack.getItem();
        String id = BuiltInRegistries.ITEM.getKey(item).getPath();
        String name = normalize(stack.getHoverName().getString());
        double score = 0.0D;
        if ((q.contains("espada") || q.contains("sable")) && item instanceof SwordItem) {
            score = 10.0D;
        } else if (q.contains("pico") && item instanceof PickaxeItem) {
            score = 10.0D;
        } else if (q.contains("hacha") && item instanceof AxeItem) {
            score = 10.0D;
        } else if (q.contains("pala") && item instanceof ShovelItem) {
            score = 10.0D;
        } else if (q.contains("azada") && item instanceof HoeItem) {
            score = 10.0D;
        } else if (q.contains("arco") && item instanceof BowItem) {
            score = 10.0D;
        } else if (q.contains("ballesta") && item instanceof CrossbowItem) {
            score = 10.0D;
        } else if (q.contains("escudo") && item instanceof ShieldItem) {
            score = 10.0D;
        } else if (q.contains("comida") && stack.getFoodProperties(null) != null) {
            score = 8.0D;
        } else if ((q.contains("bloque") || q.contains("bloques")) && item instanceof BlockItem) {
            score = 8.0D;
        }
        for (String[] pair : ALIASES) {
            if (!q.contains(pair[0])) {
                continue;
            }
            if (id.equals(pair[1])) {
                score = Math.max(score, 11.0D);
            } else if (id.contains(pair[1]) || name.contains(pair[0])) {
                score = Math.max(score, 9.0D);
            }
        }
        String idQuery = q.replace(' ', '_');
        if (id.contains(idQuery)) {
            score = Math.max(score, 12.0D);
        } else if (name.contains(q)) {
            score = Math.max(score, 9.0D);
        }
        if (score <= 0.0D) {
            return 0.0D;
        }
        // Ties: a better tier wins, and a full stack beats a leftover.
        if (item instanceof TieredItem tiered) {
            score += tiered.getTier().getAttackDamageBonus() * 0.01D;
        }
        score += Math.min(stack.getCount(), 64) * 0.0001D;
        return score;
    }

    /**
     * Equips whatever the player named. Returns the equipped stack's name, or
     * null when the bag has nothing that matches.
     */
    public static String equip(ServerPlayer player, String query, boolean offhand) {
        int slot = findSlotFor(player, query);
        if (slot < 0) {
            return null;
        }
        if (offhand) {
            ItemStack wanted = player.getInventory().items.get(slot);
            ItemStack previous = player.getOffhandItem().copy();
            player.getInventory().items.set(slot, previous);
            player.setItemInHand(InteractionHand.OFF_HAND, wanted);
            player.containerMenu.broadcastChanges();
            player.inventoryMenu.broadcastChanges();
            return wanted.getHoverName().getString();
        }
        selectOrSwap(player, slot);
        return player.getMainHandItem().getHoverName().getString();
    }

    /** Lowercase, no accents, trimmed: "Pico de Hierro" -> "pico de hierro". */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT).trim();
        StringBuilder out = new StringBuilder(lower.length());
        for (char c : lower.toCharArray()) {
            out.append(switch (c) {
                case 'á' -> 'a';
                case 'é' -> 'e';
                case 'í' -> 'i';
                case 'ó' -> 'o';
                case 'ú', 'ü' -> 'u';
                case 'ñ' -> 'n';
                default -> c;
            });
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Shield
    // ------------------------------------------------------------------

    /** Moves a shield to the off hand when there is one; keeps the old item. */
    public static boolean ensureShield(ServerPlayer player) {
        if (player.getOffhandItem().getItem() instanceof ShieldItem) {
            return true;
        }
        List<ItemStack> items = player.getInventory().items;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getItem() instanceof ShieldItem) {
                ItemStack shield = items.get(i);
                items.set(i, player.getOffhandItem());
                player.setItemInHand(InteractionHand.OFF_HAND, shield);
                return true;
            }
        }
        return false;
    }

    /** Starts or stops blocking. Vanilla requires holding the use input. */
    public static boolean setShieldBlocking(ServerPlayer player, boolean blocking) {
        if (!(player.getOffhandItem().getItem() instanceof ShieldItem)) {
            return false;
        }
        if (blocking) {
            player.startUsingItem(InteractionHand.OFF_HAND);
        } else {
            player.stopUsingItem();
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Ranged
    // ------------------------------------------------------------------

    /** Moves a bow, crossbow or trident to the main hand. */
    public static boolean equipRanged(ServerPlayer player) {
        return equipItem(player, stack -> stack.getItem() instanceof BowItem
                || stack.getItem() instanceof CrossbowItem
                || stack.getItem() instanceof TridentItem);
    }

    /** Moves the first inventory item matching the predicate to the main hand. */
    public static boolean equipItem(ServerPlayer player, java.util.function.Predicate<ItemStack> match) {
        List<ItemStack> items = player.getInventory().items;
        for (int i = 0; i < items.size(); i++) {
            if (match.test(items.get(i))) {
                selectOrSwap(player, i);
                return true;
            }
        }
        return false;
    }

    public static int arrowCount(ServerPlayer player) {
        int arrows = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(Items.ARROW) || stack.is(Items.SPECTRAL_ARROW)) {
                arrows += stack.getCount();
            }
        }
        return arrows;
    }

    // ------------------------------------------------------------------
    // Consumables
    // ------------------------------------------------------------------

    /**
     * Uses the best available consumable.
     *
     * @param kind {@code heal} (food or golden apple), {@code potion} or
     *             {@code any}
     * @return the item name that was used, or null when nothing applied
     */
    public static String useConsumable(ServerPlayer player, String kind) {
        String wanted = kind == null ? "any" : kind.toLowerCase(java.util.Locale.ROOT);

        if (!wanted.equals("potion")) {
            String golden = consumeGoldenApple(player);
            if (golden != null) {
                return golden;
            }
        }
        if (!wanted.equals("heal")) {
            String potion = drinkPotion(player);
            if (potion != null) {
                return potion;
            }
        }
        if (!wanted.equals("potion") && player.getFoodData().getFoodLevel() < 20) {
            return null;
        }
        return null;
    }

    private static String consumeGoldenApple(ServerPlayer player) {
        List<ItemStack> items = player.getInventory().items;
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE)) {
                return consumeAt(player, items, i);
            }
        }
        return null;
    }

    private static String drinkPotion(ServerPlayer player) {
        List<ItemStack> items = player.getInventory().items;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getItem() instanceof PotionItem) {
                return consumeAt(player, items, i);
            }
        }
        return null;
    }

    /**
     * Applies one consumable. {@code finishUsingItem} is the vanilla path used
     * when a player completes eating or drinking: it applies the effects and
     * returns the remainder (a glass bottle, or nothing for food).
     */
    private static String consumeAt(ServerPlayer player, List<ItemStack> items, int index) {
        ItemStack stack = items.get(index);
        String name = stack.getHoverName().getString();
        ItemStack result = stack.finishUsingItem(player.serverLevel(), player);
        items.set(index, result.isEmpty() ? ItemStack.EMPTY : result);
        return name;
    }

    // ------------------------------------------------------------------
    // Tactics
    // ------------------------------------------------------------------

    /**
     * True when the companion should disengage: low health, or wounded while
     * facing several hostiles.
     */
    public static boolean shouldRetreat(ServerPlayer player, int hostiles) {
        float health = player.getHealth();
        if (health <= RETREAT_HEALTH) {
            return true;
        }
        return health <= RETREAT_OUTNUMBERED && hostiles >= 3;
    }

    /** Unit vector pointing away from the threat, or empty when it overlaps. */
    public static double[] awayFrom(ServerPlayer player, Entity threat) {
        double dx = player.getX() - threat.getX();
        double dz = player.getZ() - threat.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.001D) {
            return new double[]{0.0D, 1.0D};
        }
        return new double[]{dx / length, dz / length};
    }

    /** Ids of the weapons available, for status output. */
    public static List<String> loadout(ServerPlayer player) {
        List<String> list = new ArrayList<>();
        for (ItemStack stack : player.getInventory().items) {
            if (weaponScore(stack) > 0.0D) {
                list.add(stack.getHoverName().getString() + " x" + stack.getCount());
            }
        }
        if (player.getOffhandItem().getItem() instanceof ShieldItem) {
            list.add("escudo (offhand)");
        }
        return list;
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    /**
     * Selects a hotbar slot, or swaps an inventory item into the held slot when
     * it lives outside the hotbar, without ever dropping an item.
     */
    /**
     * Puts a slot in the main hand, swapping it in from the backpack when needed.
     *
     * <p>The broadcast is not cosmetic: without it the inventory change never
     * reaches the client, so the companion looked like it had not equipped
     * anything even though the server had already swapped the item.</p>
     */
    private static void selectOrSwap(ServerPlayer player, int slot) {
        if (slot < 0) {
            return;
        }
        if (slot >= 9) {
            List<ItemStack> items = player.getInventory().items;
            int selected = player.getInventory().selected;
            ItemStack current = items.get(selected);
            items.set(selected, items.get(slot));
            items.set(slot, current);
            slot = selected;
        }
        player.getInventory().selected = slot;
        player.containerMenu.broadcastChanges();
        player.inventoryMenu.broadcastChanges();
    }
}
