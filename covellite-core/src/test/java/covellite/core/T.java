package covellite.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Minimal test harness. No external dependencies.
 */
public final class T {
    private T() {}

    public static void equal(String message, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    public static void isTrue(String message, boolean condition) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void fail(String message) {
        throw new AssertionError(message);
    }

    public static void expectThrows(String message, Class<? extends Throwable> expected, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            if (expected.isInstance(t)) {
                return;
            }
            throw new AssertionError(message + " expected " + expected.getName() + " got " + t.getClass().getName(), t);
        }
        throw new AssertionError(message + " expected " + expected.getName() + " but no exception was thrown");
    }

    public static void within(String message, double expected, double actual, double epsilon) {
        if (Math.abs(expected - actual) > epsilon) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    public static final class Counter {
        public int count = 0;
    }

    public static final class Log {
        private final List<String> lines = new ArrayList<>();

        public void add(String line) {
            lines.add(line);
        }

        public List<String> lines() {
            return lines;
        }

        public String join(String sep) {
            return String.join(sep, lines);
        }
    }
}
