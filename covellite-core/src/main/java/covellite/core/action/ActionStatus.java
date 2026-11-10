package covellite.core.action;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable snapshot of an action's status.
 */
public final class ActionStatus {
    private final String id;
    private final ActionState state;
    private final String type;
    private final long createdAt;
    private final Long startedAt;
    private final Long finishedAt;
    private final String message;

    public ActionStatus(String id, ActionState state, String type, long createdAt,
                        Long startedAt, Long finishedAt, String message) {
        this.id = Objects.requireNonNull(id);
        this.state = Objects.requireNonNull(state);
        this.type = Objects.requireNonNull(type);
        this.createdAt = createdAt;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.message = message;
    }

    public String id() { return id; }
    public ActionState state() { return state; }
    public String type() { return type; }
    public long createdAt() { return createdAt; }
    public Long startedAt() { return startedAt; }
    public Long finishedAt() { return finishedAt; }
    public String message() { return message; }

    public boolean isTerminal() {
        return state == ActionState.SUCCEEDED
                || state == ActionState.FAILED
                || state == ActionState.CANCELLED
                || state == ActionState.TIMED_OUT
                || state == ActionState.REJECTED;
    }

    public boolean isLive() {
        return state == ActionState.QUEUED || state == ActionState.RUNNING;
    }

    public ActionStatus withState(ActionState newState) {
        return new ActionStatus(id, newState, type, createdAt, startedAt, finishedAt, message);
    }

    public ActionStatus withState(ActionState newState, String message) {
        return new ActionStatus(id, newState, type, createdAt, startedAt, finishedAt, message);
    }

    public ActionStatus started(long at) {
        return new ActionStatus(id, ActionState.RUNNING, type, createdAt, at, finishedAt, message);
    }

    public ActionStatus finished(ActionState finalState, long at, String message) {
        return new ActionStatus(id, finalState, type, createdAt, startedAt, at, message);
    }

    @Override
    public String toString() {
        return "ActionStatus{" + id + ", " + state + ", type=" + type + "}";
    }
}
