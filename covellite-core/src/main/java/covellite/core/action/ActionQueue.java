package covellite.core.action;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Bounded, deterministic action queue.
 *
 * <p>Actions advance one step per {@link #pump(long)} call. Only one action runs
 * at a time; new submissions are queued until capacity is reached. Every action
 * has a creation-time timeout; actions that do not finish before their deadline
 * are marked {@link ActionState#TIMED_OUT}.</p>
 *
 * <p>Threading: submit/cancel/status may be called from any thread. pump must be
 * called from a single thread (the game tick thread). Synchronization is kept
 * coarse because tick work is tiny.</p>
 */
public final class ActionQueue {
    private final int capacity;
    private final int maxHistory;
    private final Consumer<ActionStatus> listener;
    private final Runnable emergencyHook;
    private final AtomicLong idCounter = new AtomicLong();

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Deque<Entry> queue = new ArrayDeque<>();
    private Entry running = null;
    private boolean emergency = false;

    public ActionQueue(int capacity) {
        this(capacity, 64, s -> {}, () -> {});
    }

    public ActionQueue(int capacity, int maxHistory,
                       Consumer<ActionStatus> listener, Runnable emergencyHook) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        if (maxHistory < 1) throw new IllegalArgumentException("maxHistory must be >= 1");
        this.capacity = capacity;
        this.maxHistory = maxHistory;
        this.listener = Objects.requireNonNull(listener);
        this.emergencyHook = Objects.requireNonNull(emergencyHook);
    }

    public synchronized ActionStatus submit(Action action, long timeoutMs) throws ActionRejectedException {
        return submit(action, timeoutMs, System.currentTimeMillis());
    }

    public synchronized ActionStatus submit(Action action, long timeoutMs, long now) throws ActionRejectedException {
        if (emergency) {
            ActionStatus rejected = reject(action.type(), "emergency_stop_active");
            throw new ActionRejectedException("emergency_stop_active");
        }
        if (timeoutMs <= 0) {
            throw new ActionRejectedException("invalid_timeout");
        }
        if (queue.size() >= capacity) {
            ActionStatus rejected = reject(action.type(), "queue_full");
            throw new ActionRejectedException("queue_full");
        }

        String id = nextId();
        ActionStatus status = new ActionStatus(id, ActionState.QUEUED, action.type(), now, null, null, null);
        Entry entry = new Entry(action, status, now + timeoutMs);
        entries.put(id, entry);
        queue.add(entry);
        emit(status);
        return status;
    }

    public synchronized void pump(long now) {
        if (emergency) {
            return;
        }

        // Age out queued actions that never got a chance to run.
        Iterator<Entry> it = queue.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (now >= e.deadline) {
                it.remove();
                finish(e, ActionState.TIMED_OUT, now, "queued_timeout");
            }
        }

        if (running == null && !queue.isEmpty()) {
            running = queue.poll();
            running.status = running.status.started(now);
            emit(running.status);
            safeStart(running);
        }

        if (running != null) {
            Entry e = running;
            if (now >= e.deadline) {
                safeStop(e);
                finish(e, ActionState.TIMED_OUT, now, "timeout");
                running = null;
                return;
            }

            Step step;
            try {
                step = e.action.tick();
            } catch (Throwable t) {
                safeStop(e);
                finish(e, ActionState.FAILED, now, "tick_exception: " + t.getMessage());
                running = null;
                return;
            }

            if (step == Step.DONE) {
                safeStop(e);
                finish(e, ActionState.SUCCEEDED, now, null);
                running = null;
            } else if (step == Step.FAIL) {
                safeStop(e);
                finish(e, ActionState.FAILED, now, "failed");
                running = null;
            }
            // CONTINUE -> keep running
        }
    }

    public synchronized boolean cancel(String id) {
        Entry e = entries.get(id);
        if (e == null) {
            return false;
        }
        if (e.status.isTerminal()) {
            return false;
        }
        if (e == running) {
            safeStop(e);
            finish(e, ActionState.CANCELLED, System.currentTimeMillis(), "cancelled");
            running = null;
            return true;
        }
        if (queue.remove(e)) {
            finish(e, ActionState.CANCELLED, System.currentTimeMillis(), "cancelled");
            return true;
        }
        return false;
    }

    /**
     * Cancels every queued and running action without enabling the emergency
     * latch. Intended for an explicit player-requested stop.
     */
    public synchronized void cancelAll() {
        long now = System.currentTimeMillis();
        if (running != null) {
            Entry e = running;
            safeStop(e);
            finish(e, ActionState.CANCELLED, now, "cancelled");
            running = null;
        }
        Iterator<Entry> it = queue.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            it.remove();
            finish(e, ActionState.CANCELLED, now, "cancelled");
        }
    }

    public synchronized void emergencyStop() {
        if (emergency) {
            return;
        }
        emergency = true;
        long now = System.currentTimeMillis();

        if (running != null) {
            Entry e = running;
            safeStop(e);
            finish(e, ActionState.CANCELLED, now, "emergency_stop");
            running = null;
        }

        Iterator<Entry> it = queue.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            it.remove();
            finish(e, ActionState.CANCELLED, now, "emergency_stop");
        }

        try {
            emergencyHook.run();
        } catch (Throwable ignored) {
            // Hook must not break the queue.
        }
    }

    public synchronized void resetEmergencyStop() {
        emergency = false;
    }

    public boolean isEmergencyStopped() {
        return emergency;
    }

    public ActionStatus status(String id) {
        Entry e = entries.get(id);
        return e == null ? null : e.status;
    }

    public synchronized List<ActionStatus> liveStatuses() {
        List<ActionStatus> list = new ArrayList<>();
        for (Entry e : queue) {
            list.add(e.status);
        }
        if (running != null) {
            list.add(running.status);
        }
        return list;
    }

    public synchronized List<ActionStatus> history() {
        return entries.values().stream()
                .filter(e -> e.status.isTerminal())
                .map(e -> e.status)
                .limit(maxHistory)
                .toList();
    }

    private void safeStart(Entry e) {
        try {
            Step step = e.action.start();
            if (step == Step.DONE) {
                safeStop(e);
                finish(e, ActionState.SUCCEEDED, System.currentTimeMillis(), null);
                running = null;
            } else if (step == Step.FAIL) {
                safeStop(e);
                finish(e, ActionState.FAILED, System.currentTimeMillis(), "start_failed");
                running = null;
            }
        } catch (Throwable t) {
            safeStop(e);
            finish(e, ActionState.FAILED, System.currentTimeMillis(), "start_exception: " + t.getMessage());
            running = null;
        }
    }

    private void safeStop(Entry e) {
        try {
            e.action.stop();
        } catch (Throwable ignored) {
            // stop() is best-effort cleanup.
        }
    }

    private void finish(Entry e, ActionState state, long now, String message) {
        e.status = e.status.finished(state, now, message);
        trimHistory();
        emit(e.status);
    }

    private void trimHistory() {
        List<Entry> finished = entries.values().stream()
                .filter(e -> e.status.isTerminal())
                .sorted((a, b) -> Long.compare(b.status.finishedAt(), a.status.finishedAt()))
                .toList();
        if (finished.size() > maxHistory) {
            for (int i = maxHistory; i < finished.size(); i++) {
                entries.remove(finished.get(i).status.id());
            }
        }
    }

    private ActionStatus reject(String type, String reason) {
        String id = nextId();
        long now = System.currentTimeMillis();
        ActionStatus status = new ActionStatus(id, ActionState.REJECTED, type, now, null, now, reason);
        entries.put(id, new Entry(null, status, now));
        emit(status);
        return status;
    }

    private void emit(ActionStatus status) {
        try {
            listener.accept(status);
        } catch (Throwable ignored) {
            // Listener must not break queue operations.
        }
    }

    private String nextId() {
        return Long.toHexString(idCounter.incrementAndGet());
    }

    private static final class Entry {
        final Action action;
        ActionStatus status;
        final long deadline;

        Entry(Action action, ActionStatus status, long deadline) {
            this.action = action;
            this.status = status;
            this.deadline = deadline;
        }
    }
}
