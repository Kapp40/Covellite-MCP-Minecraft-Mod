package covellite.agent.reflex;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Picks the most urgent reflex that is allowed to fire right now.
 *
 * <p>Rules, in order: only reflexes whose condition holds compete; the highest
 * priority wins; a reflex still inside its cooldown is skipped so it cannot
 * starve a lower one forever. Every shot is kept in a small history so
 * {@code /cove reflexes} can explain what happened and when.</p>
 */
public final class ReflexEngine {

    /** One reflex that fired. */
    public record Shot(String id, int priority, long tick, String action, String description) {
    }

    private static final int HISTORY_LIMIT = 40;

    private final List<Reflex> reflexes;
    private final Map<String, Long> lastFired = new HashMap<>();
    private final Deque<Shot> history = new ArrayDeque<>();

    public ReflexEngine(List<Reflex> reflexes) {
        this.reflexes = List.copyOf(reflexes);
    }

    public List<Reflex> reflexes() {
        return this.reflexes;
    }

    /** Returns the shot that should be applied, or empty when all is calm. */
    public synchronized Optional<Shot> evaluate(ReflexInputs inputs, long tick) {
        List<Reflex> candidates = new ArrayList<>();
        for (Reflex reflex : this.reflexes) {
            if (reflex.condition().test(inputs)) {
                candidates.add(reflex);
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        candidates.sort((a, b) -> Integer.compare(b.priority(), a.priority()));
        for (Reflex reflex : candidates) {
            Long last = this.lastFired.get(reflex.id());
            if (last != null && tick - last < reflex.cooldownTicks()) {
                continue;
            }
            Shot shot = new Shot(reflex.id(), reflex.priority(), tick, reflex.action(),
                    reflex.description());
            this.lastFired.put(reflex.id(), tick);
            this.history.addLast(shot);
            while (this.history.size() > HISTORY_LIMIT) {
                this.history.removeFirst();
            }
            return Optional.of(shot);
        }
        return Optional.empty();
    }

    /** Shots that fired, oldest first. */
    public synchronized List<Shot> history() {
        return List.copyOf(this.history);
    }

    public synchronized void clearHistory() {
        this.history.clear();
        this.lastFired.clear();
    }

    public synchronized Map<String, Long> cooldowns() {
        return Map.copyOf(this.lastFired);
    }
}
