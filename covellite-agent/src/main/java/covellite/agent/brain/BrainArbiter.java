package covellite.agent.brain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * One body, two brains, exactly one authority.
 *
 * <p>Every request carries the brain that issued it and asks
 * {@link #mayAct(Brain)} <em>when it is executed</em>, not when it was
 * requested. A switch revokes the old brain first (bumping {@link #epoch()}), so
 * anything queued before the switch can no longer act even if it arrives after
 * the new brain took over.</p>
 *
 * <p>Pausing keeps the body idle without handing it to the other brain: memory,
 * map, reflex layer and world state stay where they are.</p>
 */
public final class BrainArbiter {

    /** Who is allowed to think for the companion. */
    public enum Brain {
        LOCAL,
        MCP;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Brain parse(String raw) {
            if (raw == null) {
                return null;
            }
            String clean = raw.trim().toLowerCase(Locale.ROOT);
            if (clean.equals("local") || clean.equals("ia") || clean.equals("interno")) {
                return LOCAL;
            }
            if (clean.equals("mcp") || clean.equals("externa") || clean.equals("externo")) {
                return MCP;
            }
            return null;
        }
    }

    private static final int LOG_LIMIT = 12;

    private Brain brain;
    private long epoch = 1L;
    private boolean revoked;
    private boolean paused;
    private String pauseReason = "";
    private final Deque<String> log = new ArrayDeque<>();

    public BrainArbiter(Brain initial) {
        this.brain = initial == null ? Brain.MCP : initial;
        record("inicio: cerebro=" + this.brain.id());
    }

    public synchronized Brain brain() {
        return this.brain;
    }

    public synchronized long epoch() {
        return this.epoch;
    }

    public synchronized boolean paused() {
        return this.paused;
    }

    public synchronized String pauseReason() {
        return this.pauseReason;
    }

    /** True while a switch is in progress: nobody may act. */
    public synchronized boolean revoked() {
        return this.revoked;
    }

    /** Authority check performed at execution time. */
    public synchronized boolean mayAct(Brain requester) {
        return !this.revoked && !this.paused && requester != null && requester == this.brain;
    }

    /** Stops all action without changing which brain owns the body. */
    public synchronized void pause(String reason) {
        this.paused = true;
        this.pauseReason = reason == null ? "" : reason;
        record("pausado: " + this.pauseReason);
    }

    public synchronized void resume() {
        if (!this.paused) {
            return;
        }
        this.paused = false;
        this.pauseReason = "";
        record("reanudado");
    }

    /** Step one of a switch: the old brain loses authority immediately. */
    public synchronized void revoke(String reason) {
        this.revoked = true;
        this.epoch++;
        record("revocado: " + (reason == null ? "" : reason));
    }

    /** Step two: the new brain owns the body from a fresh epoch. */
    public synchronized void activate(Brain target, String reason) {
        this.brain = target == null ? Brain.MCP : target;
        this.revoked = false;
        this.paused = false;
        this.pauseReason = "";
        this.epoch++;
        record("cerebro=" + this.brain.id()
                + (reason == null || reason.isBlank() ? "" : " (" + reason + ")"));
    }

    /** Most recent events, oldest first. */
    public synchronized List<String> log() {
        return new ArrayList<>(this.log);
    }

    public synchronized String describe() {
        StringBuilder text = new StringBuilder(this.brain.id());
        if (this.paused) {
            text.append(" (pausado: ").append(this.pauseReason).append(')');
        }
        if (this.revoked) {
            text.append(" (sin autoridad)");
        }
        return text.append(" · epoca ").append(this.epoch).toString();
    }

    private void record(String line) {
        this.log.addLast(line);
        while (this.log.size() > LOG_LIMIT) {
            this.log.removeFirst();
        }
    }
}
