package covellite.agent.social;

/**
 * What the world looks like from Cove's point of view when it considers
 * speaking or acting on its own.
 *
 * <p>Plain data, produced on the server tick and read on the agent thread, so
 * the deliberation never touches level state.</p>
 */
public record SocialSignals(
        long nowMs,
        int humansOnline,
        double nearestHumanDistance,
        float health,
        int food,
        boolean busy,
        boolean night,
        boolean taskFinished,
        double spawnDistance,
        String biome,
        String activity,
        long msSinceHumanChat,
        long msSinceCoveSpoke) {

    /** Signals for a world with nobody around: the mind stays silent. */
    public static SocialSignals quiet(long nowMs) {
        return new SocialSignals(nowMs, 0, -1.0D, 20.0F, 20, false, false, false, 0.0D,
                "", "", 0L, 0L);
    }

    public SocialSignals withNow(long nowMs) {
        return new SocialSignals(nowMs, this.humansOnline, this.nearestHumanDistance, this.health,
                this.food, this.busy, this.night, this.taskFinished, this.spawnDistance,
                this.biome, this.activity, this.msSinceHumanChat, this.msSinceCoveSpoke);
    }

    public SocialSignals withTaskFinished(boolean finished) {
        return new SocialSignals(this.nowMs, this.humansOnline, this.nearestHumanDistance,
                this.health, this.food, this.busy, this.night, finished, this.spawnDistance,
                this.biome, this.activity, this.msSinceHumanChat, this.msSinceCoveSpoke);
    }

    public boolean lonely() {
        return this.humansOnline <= 0;
    }

    public boolean hungry() {
        return this.food <= 6;
    }

    public boolean hurt() {
        return this.health < 8.0F;
    }

    public boolean farFromSpawn() {
        return this.spawnDistance > 220.0D;
    }

    public boolean separated() {
        return this.nearestHumanDistance > 36.0D;
    }
}
