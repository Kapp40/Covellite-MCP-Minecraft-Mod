package covellite.core.state;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Computes a structural diff between two state snapshots. Nested maps and lists
 * are compared by value; changed values are returned under the "changed" key,
 * removed top-level keys under "removed".
 */
public final class StateDiff {

    private StateDiff() {}

    public static Map<String, Object> diff(StateSnapshot oldSnap, StateSnapshot newSnap) {
        Map<String, Object> result = new HashMap<>();
        Set<String> removed = new HashSet<>();
        Map<String, Object> changed = new HashMap<>();

        Map<String, Object> oldData = oldSnap == null ? Map.of() : oldSnap.data();
        Map<String, Object> newData = newSnap == null ? Map.of() : newSnap.data();

        for (String key : oldData.keySet()) {
            if (!newData.containsKey(key)) {
                removed.add(key);
            }
        }

        for (Map.Entry<String, Object> e : newData.entrySet()) {
            String key = e.getKey();
            Object newValue = e.getValue();
            Object oldValue = oldData.get(key);
            if (!valueEquals(oldValue, newValue)) {
                changed.put(key, newValue);
            }
        }

        if (!changed.isEmpty()) {
            result.put("changed", changed);
        }
        if (!removed.isEmpty()) {
            result.put("removed", List.copyOf(removed));
        }
        result.put("timestamp", newSnap == null ? 0L : newSnap.timestamp());
        return result;
    }

    @SuppressWarnings("unchecked")
    public static boolean valueEquals(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Map<?, ?> am && b instanceof Map<?, ?> bm) {
            if (am.size() != bm.size()) return false;
            for (Map.Entry<?, ?> e : am.entrySet()) {
                Object key = e.getKey();
                if (!bm.containsKey(key)) return false;
                if (!valueEquals(e.getValue(), bm.get(key))) return false;
            }
            return true;
        }
        if (a instanceof List<?> al && b instanceof List<?> bl) {
            if (al.size() != bl.size()) return false;
            for (int i = 0; i < al.size(); i++) {
                if (!valueEquals(al.get(i), bl.get(i))) return false;
            }
            return true;
        }
        return Objects.equals(a, b);
    }
}
