package covellite.agent;

/**
 * When to reel in.
 *
 * <p>The fishing hook does not expose its bite state, so the companion watches
 * the bobber: a bite pulls it down for a moment. These rules are pure so they
 * can be tested without a world; the mod only feeds them real values.</p>
 */
public final class FishingSignal {

    /** Vertical speed that counts as the bobber being pulled under. */
    public static final double DIP_THRESHOLD = -0.05D;

    /** Ticks to wait before deciding the cast itself failed. */
    public static final int CAST_GRACE_TICKS = 20;

    private FishingSignal() {
    }

    /** True when the bobber moved down far enough to be a bite. */
    public static boolean dipped(double deltaY) {
        return deltaY < DIP_THRESHOLD;
    }

    /**
     * Reel when the bobber dipped or the wait budget ran out. Waiting forever
     * would park the companion at the water's edge.
     */
    public static boolean shouldReel(double deltaY, int waitedTicks, int maxTicks) {
        return dipped(deltaY) || waitedTicks >= maxTicks;
    }

    /** True when no bobber exists and the grace period already passed. */
    public static boolean castLost(int ticksSinceCast) {
        return ticksSinceCast > CAST_GRACE_TICKS;
    }
}
