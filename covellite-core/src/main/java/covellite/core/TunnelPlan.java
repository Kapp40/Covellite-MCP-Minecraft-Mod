package covellite.core;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure policy for ramp/gallery mining, kept out of the Minecraft classes so it
 * can be tested without a game.
 *
 * <p>Three decisions live here because they are the ones that were wrong in the
 * first version: where each ore is worth digging to, how long a descent may
 * take, and whether junk may be destroyed or only dropped.</p>
 */
public final class TunnelPlan {

    /** Hard cap on blocks broken while tunnelling. */
    public static final int MAX_DUG = 400;

    /** Floor for a tunnelling budget: a long descent alone can take thousands of ticks. */
    public static final int MIN_TICKS = 9000;

    /** Ceiling the tool schema advertises. */
    public static final int MAX_TICKS = 12000;

    /** Ticks of budget reserved for walking, scanning and the gallery itself. */
    private static final int BASE_TICKS = 2500;

    /** Ticks of budget added per block of descent. */
    private static final int TICKS_PER_BLOCK = 70;

    private static final Map<String, Integer> ORE_Y = Map.ofEntries(
            Map.entry("hierro", 16), Map.entry("iron", 16),
            Map.entry("carbon", 45), Map.entry("coal", 45),
            Map.entry("oro", -16), Map.entry("gold", -16),
            Map.entry("diamante", -58), Map.entry("diamond", -58),
            Map.entry("redstone", -58),
            Map.entry("cobre", 48), Map.entry("copper", 48),
            Map.entry("lapiz", 0), Map.entry("lapislazuli", 0), Map.entry("lapis", 0));

    /** Materials a tunnel should never be opened for: they are not found by digging down. */
    private static final List<String> NO_TUNNEL = List.of(
            "piedra", "stone", "madera", "wood", "tronco", "esmeralda", "emerald", "obsidiana");

    private TunnelPlan() {
    }

    /**
     * Feet-level Y where the ore is most common in 1.21 overworld generation, or
     * {@code null} when digging down makes no sense (stone, wood, emerald...).
     */
    public static Integer targetY(String what) {
        if (what == null || what.isBlank()) {
            return null;
        }
        String key = normalize(what);
        if (NO_TUNNEL.contains(key)) {
            return null;
        }
        Integer y = ORE_Y.get(key);
        if (y != null) {
            return y;
        }
        // Terms from the generated Minecraft dictionary ("mena de hierro",
        // "mineral de diamante") carry the ore in the middle of the name.
        for (Map.Entry<String, Integer> entry : ORE_Y.entrySet()) {
            if (key.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Budget for a run that starts at {@code playerY} heading to {@code targetY}.
     * A 120-block descent needs more than the 6000 ticks the agent used to send,
     * and that was one reason mining looked like it stopped by itself.
     */
    public static int timeoutFor(double playerY, Integer targetY, int requested) {
        int budget = Math.max(requested, BASE_TICKS);
        if (targetY != null) {
            double descent = Math.max(0.0D, playerY - targetY);
            budget = Math.max(budget, BASE_TICKS + (int) Math.round(descent * TICKS_PER_BLOCK));
        }
        if (budget < MIN_TICKS) {
            budget = MIN_TICKS;
        }
        return Math.min(MAX_TICKS, budget);
    }

    /**
     * Whether spoil may be destroyed. The first version silently deleted stacks
     * from the companion's inventory, including anything the player had given
     * it; now that only happens when it was explicitly asked for.
     */
    public static boolean shouldVoidSpoil(boolean requested, int emptySlots) {
        return requested && emptySlots <= 3;
    }

    /** True when the backpack has no room left for tunnel spoil. */
    public static boolean isFull(int emptySlots) {
        return emptySlots <= 1;
    }

    private static String normalize(String text) {
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return plain.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }
}
