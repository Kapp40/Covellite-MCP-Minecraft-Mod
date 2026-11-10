package covellite.core;

import covellite.core.event.EventLog;

import java.util.Map;

public final class EventLogTest {

    public static void run() {
        testAddAndRecent();
        testSince();
        testCapacity();
        System.out.println("  EventLog: all tests passed");
    }

    private static void testAddAndRecent() {
        EventLog log = new EventLog(10);
        log.add("chat", Map.of("message", "hola"));
        log.add("move", Map.of("x", 1));
        T.equal("recent size", 2, log.recent(10).size());
        T.equal("recent order", "move", log.recent(1).get(0).type());
    }

    private static void testSince() {
        EventLog log = new EventLog(10);
        log.add("a", Map.of());
        long seq = log.latestSequence();
        log.add("b", Map.of());
        log.add("c", Map.of());
        T.equal("since after first", 2, log.since(seq, 10).size());
        T.equal("since types", "b", log.since(seq, 10).get(0).type());
    }

    private static void testCapacity() {
        EventLog log = new EventLog(2);
        log.add("a", Map.of());
        log.add("b", Map.of());
        log.add("c", Map.of());
        T.equal("capacity size", 2, log.size());
        T.equal("capacity evicts oldest", "b", log.recent(10).get(0).type());
        T.equal("capacity keeps newest", "c", log.recent(10).get(1).type());
    }
}
