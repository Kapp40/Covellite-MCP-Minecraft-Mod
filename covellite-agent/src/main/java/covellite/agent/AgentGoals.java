package covellite.agent;

import covellite.core.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent, hierarchical goals.
 *
 * <p>A goal is what the player asked for; its steps are what the agent decided
 * to do about it. Both survive a restart because they are written to
 * {@code goals.json} inside the world's agent directory, so an interrupted task
 * can be resumed instead of being forgotten when the model context is trimmed.</p>
 *
 * <p>Progress is explicit: only {@link #advance(String)} marks a step as done,
 * which keeps "I said I did it" separate from "the tool reported success".</p>
 */
public final class AgentGoals {

    /** Lifecycle of a goal. */
    public enum Status { ACTIVE, PAUSED, DONE, CANCELLED }

    /** One step of a goal. */
    public record Step(String text, boolean done) {
        public Map<String, Object> toMap() {
            return Map.of("text", this.text, "done", this.done);
        }

        public static Step fromMap(Map<String, Object> map) {
            boolean done = Boolean.TRUE.equals(map.get("done"));
            return new Step(map.get("text") == null ? "" : String.valueOf(map.get("text")), done);
        }
    }

    /** A goal with its steps and status. */
    public record Goal(String id, String text, List<Step> steps, Status status,
                       long createdAt, long updatedAt) {

        public int doneCount() {
            int done = 0;
            for (Step step : this.steps) {
                if (step.done()) {
                    done++;
                }
            }
            return done;
        }

        public int progressPercent() {
            if (this.steps.isEmpty()) {
                return this.status == Status.DONE ? 100 : 0;
            }
            return Math.round(100.0F * doneCount() / this.steps.size());
        }

        public String nextStep() {
            for (Step step : this.steps) {
                if (!step.done()) {
                    return step.text();
                }
            }
            return "";
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", this.id);
            map.put("text", this.text);
            List<Object> stepMaps = new ArrayList<>();
            for (Step step : this.steps) {
                stepMaps.add(step.toMap());
            }
            map.put("steps", stepMaps);
            map.put("status", this.status.name());
            map.put("created", this.createdAt);
            map.put("updated", this.updatedAt);
            return map;
        }

        public static Goal fromMap(Map<String, Object> map) {
            List<Step> steps = new ArrayList<>();
            if (map.get("steps") instanceof List<?> raw) {
                for (Object item : raw) {
                    if (item instanceof Map<?, ?> stepMap) {
                        Map<String, Object> typed = new LinkedHashMap<>();
                        stepMap.forEach((key, value) -> typed.put(String.valueOf(key), value));
                        steps.add(Step.fromMap(typed));
                    }
                }
            }
            Status status;
            try {
                status = Status.valueOf(String.valueOf(map.get("status")));
            } catch (RuntimeException e) {
                status = Status.ACTIVE;
            }
            return new Goal(
                    String.valueOf(map.get("id")),
                    map.get("text") == null ? "" : String.valueOf(map.get("text")),
                    steps, status,
                    number(map.get("created")), number(map.get("updated")));
        }

        private static long number(Object value) {
            return value instanceof Number n ? n.longValue() : 0L;
        }
    }

    private final Path file;
    private final List<Goal> goals = new ArrayList<>();

    public AgentGoals(Path directory) {
        this.file = directory.resolve("goals.json");
        load();
    }

    public synchronized Goal create(String text, List<String> steps) {
        String body = text == null ? "" : text.trim();
        if (body.isEmpty()) {
            throw new IllegalArgumentException("goal text is required");
        }
        List<Step> parsed = new ArrayList<>();
        if (steps != null) {
            for (String raw : steps) {
                String step = raw == null ? "" : raw.trim();
                if (!step.isEmpty()) {
                    parsed.add(new Step(step, false));
                }
            }
        }
        long now = System.currentTimeMillis();
        Goal goal = new Goal("goal_" + UUID.randomUUID().toString().substring(0, 8),
                body, parsed, Status.ACTIVE, now, now);
        this.goals.add(goal);
        save();
        return goal;
    }

    public synchronized List<Goal> all() {
        return List.copyOf(this.goals);
    }

    public synchronized List<Goal> active() {
        List<Goal> result = new ArrayList<>();
        for (Goal goal : this.goals) {
            if (goal.status() == Status.ACTIVE) {
                result.add(goal);
            }
        }
        return result;
    }

    /** Marks the next pending step as done. Returns the updated goal, if any. */
    public synchronized Goal advance(String id) {
        for (int i = 0; i < this.goals.size(); i++) {
            Goal goal = this.goals.get(i);
            if (!goal.id().equals(id)) {
                continue;
            }
            List<Step> steps = new ArrayList<>(goal.steps());
            boolean advanced = false;
            for (int s = 0; s < steps.size(); s++) {
                if (!steps.get(s).done()) {
                    steps.set(s, new Step(steps.get(s).text(), true));
                    advanced = true;
                    break;
                }
            }
            Status status = goal.status();
            if (advanced && steps.stream().allMatch(Step::done)) {
                status = Status.DONE;
            }
            Goal updated = new Goal(goal.id(), goal.text(), steps, status,
                    goal.createdAt(), System.currentTimeMillis());
            this.goals.set(i, updated);
            save();
            return updated;
        }
        return null;
    }

    public synchronized boolean complete(String id) {
        return setStatus(id, Status.DONE);
    }

    public synchronized boolean pause(String id) {
        return setStatus(id, Status.PAUSED);
    }

    public synchronized boolean resume(String id) {
        return setStatus(id, Status.ACTIVE);
    }

    public synchronized boolean cancel(String id) {
        return setStatus(id, Status.CANCELLED);
    }

    public synchronized boolean forget(String id) {
        boolean removed = this.goals.removeIf(goal -> goal.id().equals(id));
        if (removed) {
            save();
        }
        return removed;
    }

    /** Compact block for the system prompt: only what is still in flight. */
    public synchronized String summary() {
        List<Goal> active = active();
        if (active.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("Objetivos activos:\n");
        int shown = 0;
        for (Goal goal : active) {
            if (shown++ >= 4 || builder.length() > 700) {
                break;
            }
            builder.append("- ").append(goal.text())
                    .append(" (").append(goal.progressPercent()).append("%, ")
                    .append(goal.doneCount()).append('/').append(goal.steps().size()).append(")\n");
            String next = goal.nextStep();
            if (!next.isEmpty()) {
                builder.append("  siguiente: ").append(next).append('\n');
            }
        }
        return builder.toString();
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("total", this.goals.size());
        map.put("active", active().size());
        map.put("file", this.file.toAbsolutePath().toString());
        List<Object> items = new ArrayList<>();
        for (Goal goal : active()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", goal.id());
            item.put("text", goal.text());
            item.put("progress", goal.progressPercent());
            items.add(item);
        }
        map.put("goals", items);
        return map;
    }

    // ------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------

    private boolean setStatus(String id, Status status) {
        for (int i = 0; i < this.goals.size(); i++) {
            Goal goal = this.goals.get(i);
            if (!goal.id().equals(id)) {
                continue;
            }
            this.goals.set(i, new Goal(goal.id(), goal.text(), goal.steps(), status,
                    goal.createdAt(), System.currentTimeMillis()));
            save();
            return true;
        }
        return false;
    }

    private void load() {
        if (!Files.exists(this.file)) {
            return;
        }
        try {
            String text = Files.readString(this.file, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return;
            }
            Object parsed = Json.parse(text);
            List<?> raw;
            if (parsed instanceof Map<?, ?> map && map.get("goals") instanceof List<?> list) {
                raw = list;
            } else if (parsed instanceof List<?> list) {
                raw = list;
            } else {
                return;
            }
            for (Object item : raw) {
                if (item instanceof Map<?, ?> goalMap) {
                    Map<String, Object> typed = new LinkedHashMap<>();
                    goalMap.forEach((key, value) -> typed.put(String.valueOf(key), value));
                    this.goals.add(Goal.fromMap(typed));
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[agent] cannot read goals " + this.file + ": " + e.getMessage());
        }
    }

    private void save() {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> items = new ArrayList<>();
        for (Goal goal : this.goals) {
            items.add(goal.toMap());
        }
        root.put("goals", items);
        try {
            if (this.file.getParent() != null) {
                Files.createDirectories(this.file.getParent());
            }
            Files.writeString(this.file, Json.pretty(root), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException | RuntimeException e) {
            System.err.println("[agent] cannot save goals " + this.file + ": " + e.getMessage());
        }
    }
}
