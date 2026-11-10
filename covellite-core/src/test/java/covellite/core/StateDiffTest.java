package covellite.core;

import covellite.core.json.Json;
import covellite.core.state.StateDiff;
import covellite.core.state.StateSnapshot;

import java.util.List;
import java.util.Map;

public final class StateDiffTest {

    public static void run() {
        testNoChanges();
        testAddedChangedRemoved();
        testNested();
        System.out.println("  StateDiff: all tests passed");
    }

    private static void testNoChanges() {
        Map<String, Object> data = Map.of("health", 20, "pos", List.of(1, 2, 3));
        StateSnapshot a = new StateSnapshot(data, 0);
        StateSnapshot b = new StateSnapshot(data, 1);
        Map<String, Object> diff = StateDiff.diff(a, b);
        T.isTrue("no changes", !diff.containsKey("changed"));
        T.isTrue("no removed", !diff.containsKey("removed"));
        T.equal("timestamp", 1L, diff.get("timestamp"));
    }

    private static void testAddedChangedRemoved() {
        StateSnapshot a = new StateSnapshot(Map.of("health", 20, "old", true), 0);
        StateSnapshot b = new StateSnapshot(Map.of("health", 19, "new", "x"), 1);
        Map<String, Object> diff = StateDiff.diff(a, b);
        @SuppressWarnings("unchecked")
        Map<String, Object> changed = (Map<String, Object>) diff.get("changed");
        @SuppressWarnings("unchecked")
        List<String> removed = (List<String>) diff.get("removed");
        T.equal("changed health", 19, changed.get("health"));
        T.equal("changed new", "x", changed.get("new"));
        T.isTrue("removed old", removed.contains("old"));
    }

    private static void testNested() {
        StateSnapshot a = new StateSnapshot(Map.of("inv", Map.of("slot0", "sword")), 0);
        StateSnapshot b = new StateSnapshot(Map.of("inv", Map.of("slot0", "pickaxe")), 1);
        Map<String, Object> diff = StateDiff.diff(a, b);
        @SuppressWarnings("unchecked")
        Map<String, Object> changed = (Map<String, Object>) diff.get("changed");
        T.isTrue("nested changed", changed.containsKey("inv"));
    }
}
