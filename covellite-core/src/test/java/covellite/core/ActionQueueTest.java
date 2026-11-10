package covellite.core;

import covellite.core.action.Action;
import covellite.core.action.ActionQueue;
import covellite.core.action.ActionRejectedException;
import covellite.core.action.ActionState;
import covellite.core.action.ActionStatus;
import covellite.core.action.Step;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ActionQueueTest {

    public static void run() throws Exception {
        testInstantAction();
        testMultiTickAction();
        testFifoOrder();
        testTimeoutBoundary();
        testNeverFinishingTimesOut();
        testCancelQueued();
        testCancelRunning();
        testCancelUnknown();
        testCapacity();
        testInvalidTimeout();
        testExceptionsDontBreakQueue();
        testQueuedExpiration();
        testEmergencyStop();
        testHistoryBounded();
        testListenerOrder();
        testConcurrentStress();
        System.out.println("  ActionQueue: all tests passed");
    }

    private static void testInstantAction() throws ActionRejectedException {
        ActionQueue q = new ActionQueue(10);
        long now = 0;
        ActionStatus s = q.submit(new InstantAction(), 1000, now);
        T.equal("instant queued", ActionState.QUEUED, s.state());
        q.pump(now);
        T.equal("instant succeeded", ActionState.SUCCEEDED, q.status(s.id()).state());
    }

    private static void testMultiTickAction() throws ActionRejectedException {
        ActionQueue q = new ActionQueue(10);
        long now = 0;
        CountAction a = new CountAction(3);
        ActionStatus s = q.submit(a, 1000, now);
        q.pump(now);
        T.equal("multi running", ActionState.RUNNING, q.status(s.id()).state());
        q.pump(++now);
        q.pump(++now);
        T.equal("multi succeeded", ActionState.SUCCEEDED, q.status(s.id()).state());
        T.equal("multi ticks", 3, a.ticks);
    }

    private static void testFifoOrder() throws ActionRejectedException {
        ActionQueue q = new ActionQueue(10);
        long now = 0;
        CountAction a1 = new CountAction(2);
        CountAction a2 = new CountAction(1);
        ActionStatus s1 = q.submit(a1, 1000, now);
        ActionStatus s2 = q.submit(a2, 1000, now);
        q.pump(now);
        T.equal("fifo first running", ActionState.RUNNING, q.status(s1.id()).state());
        T.equal("fifo second queued", ActionState.QUEUED, q.status(s2.id()).state());
        q.pump(++now);
        T.equal("fifo first done", ActionState.SUCCEEDED, q.status(s1.id()).state());
        q.pump(++now);
        T.equal("fifo second done", ActionState.SUCCEEDED, q.status(s2.id()).state());
    }

    private static void testTimeoutBoundary() throws ActionRejectedException {
        long now = 0;
        ActionQueue q = new ActionQueue(10);
        CountAction a = new CountAction(5);
        ActionStatus s = q.submit(a, 5, now);
        q.pump(now);
        for (int i = 0; i < 4; i++) {
            q.pump(++now);
        }
        T.equal("timeout 5 succeeds", ActionState.SUCCEEDED, q.status(s.id()).state());

        q = new ActionQueue(10);
        a = new CountAction(5);
        now = 0;
        s = q.submit(a, 4, now);
        q.pump(now);
        for (int i = 0; i < 4; i++) {
            q.pump(++now);
        }
        T.equal("timeout 4 times out", ActionState.TIMED_OUT, q.status(s.id()).state());
    }

    private static void testNeverFinishingTimesOut() throws ActionRejectedException {
        ActionQueue q = new ActionQueue(10);
        long now = 0;
        ActionStatus s = q.submit(new InfiniteAction(), 5, now);
        q.pump(now);
        T.equal("never finishing running", ActionState.RUNNING, q.status(s.id()).state());
        q.pump(now + 5);
        T.equal("never finishing timed out", ActionState.TIMED_OUT, q.status(s.id()).state());
    }

    private static void testCancelQueued() throws ActionRejectedException {
        ActionQueue q = new ActionQueue(10);
        long now = 0;
        CountAction a = new CountAction(1);
        ActionStatus s = q.submit(a, 1000, now);
        T.isTrue("cancel queued", q.cancel(s.id()));
        T.equal("cancelled state", ActionState.CANCELLED, q.status(s.id()).state());
    }

    private static void testCancelRunning() throws ActionRejectedException {
        ActionQueue q = new ActionQueue(10);
        long now = 0;
        CountAction a = new CountAction(10);
        ActionStatus s = q.submit(a, 1000, now);
        q.pump(now);
        T.equal("running before cancel", ActionState.RUNNING, q.status(s.id()).state());
        T.isTrue("cancel running", q.cancel(s.id()));
        T.equal("cancelled running", ActionState.CANCELLED, q.status(s.id()).state());
    }

    private static void testCancelUnknown() {
        ActionQueue q = new ActionQueue(10);
        T.isTrue("cancel unknown false", !q.cancel("nope"));
        ActionStatus s = q.status("nope");
        T.equal("unknown status null", null, s);
    }

    private static void testCapacity() throws ActionRejectedException {
        ActionQueue q = new ActionQueue(2);
        long now = 0;
        q.submit(new InfiniteAction(), 1000, now);
        q.submit(new InfiniteAction(), 1000, now);
        try {
            q.submit(new InfiniteAction(), 1000, now);
            T.fail("should reject when full");
        } catch (ActionRejectedException e) {
            T.equal("rejected reason", "queue_full", e.reason());
        }
    }

    private static void testInvalidTimeout() {
        ActionQueue q = new ActionQueue(10);
        try {
            q.submit(new InstantAction(), 0, 0);
            T.fail("should reject invalid timeout");
        } catch (ActionRejectedException e) {
            T.equal("invalid timeout reason", "invalid_timeout", e.reason());
        }
    }

    private static void testExceptionsDontBreakQueue() throws ActionRejectedException {
        ActionQueue q = new ActionQueue(10);
        long now = 0;
        ActionStatus bad = q.submit(new ThrowingAction(true, false, false), 1000, now);
        ActionStatus good = q.submit(new InstantAction(), 1000, now);
        q.pump(now);
        T.equal("start exception failed", ActionState.FAILED, q.status(bad.id()).state());
        q.pump(++now);
        T.equal("next action still runs", ActionState.SUCCEEDED, q.status(good.id()).state());

        q = new ActionQueue(10);
        now = 0;
        bad = q.submit(new ThrowingAction(false, true, false), 1000, now);
        good = q.submit(new InstantAction(), 1000, now);
        q.pump(now);
        q.pump(++now);
        T.equal("tick exception failed", ActionState.FAILED, q.status(bad.id()).state());
        q.pump(++now);
        T.equal("next after tick exception runs", ActionState.SUCCEEDED, q.status(good.id()).state());
    }

    private static void testQueuedExpiration() throws ActionRejectedException {
        ActionQueue q = new ActionQueue(10);
        long now = 0;
        q.submit(new CountAction(1), 5, now);
        ActionStatus s2 = q.submit(new InstantAction(), 3, now);
        q.pump(now);
        q.pump(now + 3);
        T.equal("queued expired", ActionState.TIMED_OUT, q.status(s2.id()).state());
    }

    private static void testEmergencyStop() throws ActionRejectedException {
        List<String> calls = new ArrayList<>();
        ActionQueue q = new ActionQueue(10, 64, s -> {}, () -> calls.add("hook"));
        long now = 0;
        q.submit(new InfiniteAction(), 1000, now);
        q.submit(new InfiniteAction(), 1000, now);
        q.pump(now);
        q.emergencyStop();
        T.isTrue("emergency latched", q.isEmergencyStopped());
        T.equal("hook called once", 1, calls.size());
        try {
            q.submit(new InstantAction(), 1000, now);
            T.fail("submit after emergency");
        } catch (ActionRejectedException e) {
            T.equal("emergency reject reason", "emergency_stop_active", e.reason());
        }
        q.resetEmergencyStop();
        T.isTrue("emergency reset", !q.isEmergencyStopped());
    }

    private static void testHistoryBounded() throws ActionRejectedException {
        ActionQueue q = new ActionQueue(10, 2, s -> {}, () -> {});
        long now = 0;
        for (int i = 0; i < 5; i++) {
            ActionStatus s = q.submit(new InstantAction(), 1000, now);
            q.pump(now++);
        }
        T.equal("history bounded", 2, q.history().size());
    }

    private static void testListenerOrder() throws ActionRejectedException {
        List<ActionState> states = new ArrayList<>();
        ActionQueue q = new ActionQueue(10, 64, s -> states.add(s.state()), () -> {});
        long now = 0;
        ActionStatus s = q.submit(new InstantAction(), 1000, now);
        q.pump(now);
        T.equal("listener queued", ActionState.QUEUED, states.get(0));
        T.equal("listener running", ActionState.RUNNING, states.get(1));
        T.equal("listener succeeded", ActionState.SUCCEEDED, states.get(2));
    }

    private static void testConcurrentStress() throws Exception {
        ActionQueue q = new ActionQueue(100, 200, s -> {}, () -> {});
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(4);

        for (int t = 0; t < 4; t++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 200; i++) {
                        try {
                            q.submit(new InstantAction(), 10_000);
                            ok.incrementAndGet();
                        } catch (ActionRejectedException e) {
                            rejected.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        Thread pumper = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 2000; i++) {
                    q.pump(System.currentTimeMillis());
                    Thread.sleep(1);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        pumper.start();

        start.countDown();
        T.isTrue("concurrent done", done.await(30, TimeUnit.SECONDS));
        pumper.join(5000);

        int total = ok.get() + rejected.get();
        T.equal("total submissions", 800, total);
        T.isTrue("some completed", q.history().size() > 0);
    }

    // Helper action classes

    private static final class InstantAction implements Action {
        @Override public String type() { return "instant"; }
        @Override public Step start() { return Step.DONE; }
        @Override public Step tick() { return Step.DONE; }
    }

    private static final class CountAction implements Action {
        private final int target;
        private int ticks = 0;

        CountAction(int target) { this.target = target; }

        @Override public String type() { return "count"; }
        @Override public Step start() { return Step.CONTINUE; }
        @Override public Step tick() {
            ticks++;
            return ticks >= target ? Step.DONE : Step.CONTINUE;
        }
    }

    private static final class InfiniteAction implements Action {
        @Override public String type() { return "infinite"; }
        @Override public Step start() { return Step.CONTINUE; }
        @Override public Step tick() { return Step.CONTINUE; }
    }

    private static final class ThrowingAction implements Action {
        private final boolean throwStart;
        private final boolean throwTick;
        private final boolean throwStop;

        ThrowingAction(boolean throwStart, boolean throwTick, boolean throwStop) {
            this.throwStart = throwStart;
            this.throwTick = throwTick;
            this.throwStop = throwStop;
        }

        @Override public String type() { return "throwing"; }
        @Override public Step start() {
            if (throwStart) throw new RuntimeException("start");
            return Step.CONTINUE;
        }
        @Override public Step tick() {
            if (throwTick) throw new RuntimeException("tick");
            return Step.CONTINUE;
        }
        @Override public void stop() {
            if (throwStop) throw new RuntimeException("stop");
        }
    }
}
