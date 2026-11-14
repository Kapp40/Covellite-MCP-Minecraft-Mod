package covellite.agent.reflex;

import java.util.function.Predicate;

/**
 * One reflex: a pure condition, a priority, a cooldown and the action id the
 * mod knows how to perform.
 *
 * <p>Higher {@link #priority()} wins when several reflexes trigger at once;
 * layer 0 (survival) uses 100 down to 91 so nothing else can outrank it.</p>
 */
public record Reflex(String id, int priority, int cooldownTicks, String action,
                     Predicate<ReflexInputs> condition, String description) {

    public Reflex {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("reflex needs an id");
        }
        if (condition == null) {
            throw new IllegalArgumentException("reflex " + id + " needs a condition");
        }
    }
}
