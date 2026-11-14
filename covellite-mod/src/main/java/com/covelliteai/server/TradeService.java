package com.covelliteai.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import covellite.agent.ItemLedger;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * Villager trading performed on the companion's real inventory.
 *
 * <p>This talks to the {@link Villager} entity directly instead of opening a
 * merchant menu: menus are player-session state and cannot be driven reliably
 * from the companion. The consequences are documented rather than hidden —
 * trades consume the real cost items and hand over the real result, uses are
 * incremented, but the villager does not restock or level up from here; for that
 * the player still needs the actual trading screen.</p>
 */
public final class TradeService {

    private TradeService() {
    }

    /** Nearby villagers with their offers, so the model can choose one. */
    public static Map<String, Object> scan(ServerPlayer player, int radius) {
        List<Villager> villagers = player.serverLevel().getEntitiesOfClass(Villager.class,
                player.getBoundingBox().inflate(radius));
        List<Object> out = new ArrayList<>();
        for (Villager villager : villagers) {
            if (out.size() >= 12) {
                break;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", villager.getUUID().toString());
            entry.put("profession", profession(villager));
            entry.put("level", villager.getVillagerData().getLevel());
            entry.put("xp", villager.getVillagerXp());
            entry.put("distance", Math.round(player.distanceTo(villager)));
            List<Object> offers = new ArrayList<>();
            MerchantOffers merchantOffers = villager.getOffers();
            for (int i = 0; i < merchantOffers.size() && i < 20; i++) {
                offers.add(describe(i, merchantOffers.get(i)));
            }
            entry.put("offers", offers);
            out.add(entry);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", out.size());
        result.put("villagers", out);
        return result;
    }

    /**
     * Executes one offer: pays its cost from the inventory and stores the
     * result. Refuses when the companion cannot afford it.
     */
    public static Map<String, Object> buy(ServerPlayer player, String query, int index, int radius) {
        Villager villager = find(player, query, radius);
        if (villager == null) {
            throw new IllegalArgumentException("no villager found for " + query);
        }
        MerchantOffers offers = villager.getOffers();
        if (index < 0 || index >= offers.size()) {
            throw new IllegalArgumentException("offer index out of range (0-" + (offers.size() - 1) + ")");
        }
        MerchantOffer offer = offers.get(index);
        if (offer.isOutOfStock()) {
            throw new IllegalArgumentException("offer " + index + " is out of stock");
        }
        ItemStack costA = offer.getCostA();
        ItemStack costB = offer.getCostB();
        ItemLedger.Plan plan = ItemLedger.plan(slots(player), ItemLedger.costs(
                new ItemLedger.Slot(name(costA), costA.getCount()),
                new ItemLedger.Slot(name(costB), costB.getCount())));
        if (!plan.affordable()) {
            throw new IllegalArgumentException("missing cost: " + plan.missing()
                    + " (needs " + describeCost(costA, costB) + ")");
        }
        applyPlan(player, plan);
        ItemStack result = offer.getResult().copy();
        offer.increaseUses();
        if (!player.getInventory().add(result)) {
            player.drop(result, false);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("profession", profession(villager));
        out.put("paid", describeCost(costA, costB));
        out.put("received", name(offer.getResult()) + " x" + offer.getResult().getCount());
        out.put("uses", offer.getUses());
        out.put("max_uses", offer.getMaxUses());
        return out;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Villager find(ServerPlayer player, String query, int radius) {
        List<Villager> villagers = player.serverLevel().getEntitiesOfClass(Villager.class,
                player.getBoundingBox().inflate(radius));
        if (villagers.isEmpty()) {
            return null;
        }
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty() || needle.equals("nearest") || needle.equals("cercano")) {
            Villager best = null;
            for (Villager villager : villagers) {
                if (best == null || player.distanceToSqr(villager) < player.distanceToSqr(best)) {
                    best = villager;
                }
            }
            return best;
        }
        for (Villager villager : villagers) {
            if (villager.getUUID().toString().equals(needle)
                    || profession(villager).toLowerCase(Locale.ROOT).contains(needle)) {
                return villager;
            }
        }
        return null;
    }

    private static Map<String, Object> describe(int index, MerchantOffer offer) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("index", index);
        map.put("cost_a", name(offer.getCostA()) + " x" + offer.getCostA().getCount());
        if (!offer.getCostB().isEmpty()) {
            map.put("cost_b", name(offer.getCostB()) + " x" + offer.getCostB().getCount());
        }
        map.put("result", name(offer.getResult()) + " x" + offer.getResult().getCount());
        map.put("uses", offer.getUses() + "/" + offer.getMaxUses());
        map.put("out_of_stock", offer.isOutOfStock());
        return map;
    }

    private static String describeCost(ItemStack costA, ItemStack costB) {
        String text = name(costA) + " x" + costA.getCount();
        if (!costB.isEmpty()) {
            text += " + " + name(costB) + " x" + costB.getCount();
        }
        return text;
    }

    /** Snapshot of main inventory followed by offhand, as ledger slots. */
    private static List<ItemLedger.Slot> slots(ServerPlayer player) {
        List<ItemLedger.Slot> slots = new ArrayList<>();
        for (ItemStack stack : player.getInventory().items) {
            slots.add(new ItemLedger.Slot(stack.isEmpty() ? "" : name(stack), stack.getCount()));
        }
        for (ItemStack stack : player.getInventory().offhand) {
            slots.add(new ItemLedger.Slot(stack.isEmpty() ? "" : name(stack), stack.getCount()));
        }
        return slots;
    }

    /** Removes exactly what the ledger planned, never more. */
    private static void applyPlan(ServerPlayer player, ItemLedger.Plan plan) {
        int mainSize = player.getInventory().items.size();
        for (Map.Entry<Integer, Integer> entry : plan.takeBySlot().entrySet()) {
            int index = entry.getKey();
            int amount = entry.getValue();
            if (index < mainSize) {
                player.getInventory().items.get(index).shrink(amount);
            } else {
                int offhand = index - mainSize;
                if (offhand < player.getInventory().offhand.size()) {
                    player.getInventory().offhand.get(offhand).shrink(amount);
                }
            }
        }
    }

    private static String name(ItemStack stack) {
        if (stack.isEmpty()) {
            return "nothing";
        }
        return String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    private static String profession(Villager villager) {
        return String.valueOf(BuiltInRegistries.VILLAGER_PROFESSION
                .getKey(villager.getVillagerData().getProfession()));
    }
}
