package covellite.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which inventory slots pay a trade cost.
 *
 * <p>Pure arithmetic on slot contents, so the payment rules are testable without
 * a Minecraft server: spreading a cost across several partial stacks, refusing
 * when short, and naming what is missing.</p>
 */
public final class ItemLedger {

    /** One inventory slot: item id and how many it holds. */
    public record Slot(String item, int count) {
    }

    /**
     * Result of planning a payment.
     *
     * @param takeBySlot slot index to amount to remove; empty when unaffordable
     * @param affordable whether every cost could be covered
     * @param missing    item id that could not be covered, empty when affordable
     */
    public record Plan(Map<Integer, Integer> takeBySlot, boolean affordable, String missing) {
        public int taken() {
            int total = 0;
            for (int amount : takeBySlot.values()) {
                total += amount;
            }
            return total;
        }
    }

    private ItemLedger() {
    }

    /** Plans how to pay {@code costs} from {@code slots}, in order. */
    public static Plan plan(List<Slot> slots, List<Slot> costs) {
        int[] remaining = new int[slots.size()];
        for (int i = 0; i < slots.size(); i++) {
            remaining[i] = Math.max(0, slots.get(i).count());
        }
        Map<Integer, Integer> take = new LinkedHashMap<>();
        for (Slot cost : costs) {
            if (cost == null || cost.count() <= 0) {
                continue;
            }
            int need = cost.count();
            for (int i = 0; i < slots.size() && need > 0; i++) {
                Slot slot = slots.get(i);
                if (remaining[i] <= 0 || !slot.item().equals(cost.item())) {
                    continue;
                }
                int use = Math.min(need, remaining[i]);
                remaining[i] -= use;
                need -= use;
                take.merge(i, use, Integer::sum);
            }
            if (need > 0) {
                return new Plan(Map.of(), false, cost.item());
            }
        }
        return new Plan(Map.copyOf(take), true, "");
    }

    /** Convenience builder for a cost list. */
    public static List<Slot> costs(Slot... slots) {
        List<Slot> list = new ArrayList<>();
        for (Slot slot : slots) {
            if (slot != null && !slot.item().isEmpty() && slot.count() > 0) {
                list.add(slot);
            }
        }
        return list;
    }
}
