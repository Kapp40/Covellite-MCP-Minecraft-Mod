package covellite.agent.reflex;

/**
 * Everything a reflex may look at, as plain values.
 *
 * <p>Pure data on purpose: the mod fills it from the world, the reflex rules
 * only do arithmetic, and tests build it without a server.</p>
 */
public record ReflexInputs(
        double health,
        int food,
        int airTicks,
        boolean lavaAdjacent,
        boolean lavaAhead,
        boolean lavaBelowTarget,
        boolean lavaAboveTarget,
        boolean solidAhead,
        double fallDistance,
        boolean hasWaterBucket,
        int waterBelowBlocks,
        boolean levitating,
        boolean freezing,
        boolean inCobweb,
        boolean fallingBlockAbove,
        int primedTntDistance) {

    /** Nothing dangerous: every layer-0 reflex stays quiet. */
    public static ReflexInputs calm() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable helper so callers only set what they mean. */
    public static final class Builder {
        private double health = 20.0D;
        private int food = 20;
        private int airTicks = 300;
        private boolean lavaAdjacent;
        private boolean lavaAhead;
        private boolean lavaBelowTarget;
        private boolean lavaAboveTarget;
        private boolean solidAhead = true;
        private double fallDistance;
        private boolean hasWaterBucket;
        private int waterBelowBlocks = 99;
        private boolean levitating;
        private boolean freezing;
        private boolean inCobweb;
        private boolean fallingBlockAbove;
        private int primedTntDistance = 999;

        public Builder health(double value) {
            this.health = value;
            return this;
        }

        public Builder food(int value) {
            this.food = value;
            return this;
        }

        public Builder air(int value) {
            this.airTicks = value;
            return this;
        }

        public Builder lavaAdjacent(boolean value) {
            this.lavaAdjacent = value;
            return this;
        }

        public Builder lavaAhead(boolean value) {
            this.lavaAhead = value;
            return this;
        }

        public Builder lavaBelowTarget(boolean value) {
            this.lavaBelowTarget = value;
            return this;
        }

        public Builder lavaAboveTarget(boolean value) {
            this.lavaAboveTarget = value;
            return this;
        }

        public Builder solidAhead(boolean value) {
            this.solidAhead = value;
            return this;
        }

        public Builder fall(double distance, boolean bucket, int waterBelow) {
            this.fallDistance = distance;
            this.hasWaterBucket = bucket;
            this.waterBelowBlocks = waterBelow;
            return this;
        }

        public Builder waterBucket(boolean value) {
            this.hasWaterBucket = value;
            return this;
        }

        public Builder waterBelow(int value) {
            this.waterBelowBlocks = value;
            return this;
        }

        public Builder levitating(boolean value) {
            this.levitating = value;
            return this;
        }

        public Builder freezing(boolean value) {
            this.freezing = value;
            return this;
        }

        public Builder inCobweb(boolean value) {
            this.inCobweb = value;
            return this;
        }

        public Builder fallingBlockAbove(boolean value) {
            this.fallingBlockAbove = value;
            return this;
        }

        public Builder primedTnt(int distance) {
            this.primedTntDistance = distance;
            return this;
        }

        public ReflexInputs build() {
            return new ReflexInputs(this.health, this.food, this.airTicks, this.lavaAdjacent,
                    this.lavaAhead, this.lavaBelowTarget, this.lavaAboveTarget, this.solidAhead,
                    this.fallDistance, this.hasWaterBucket, this.waterBelowBlocks, this.levitating,
                    this.freezing, this.inCobweb, this.fallingBlockAbove, this.primedTntDistance);
        }
    }
}
