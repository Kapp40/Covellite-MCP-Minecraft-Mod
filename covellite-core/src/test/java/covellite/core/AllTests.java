package covellite.core;

public final class AllTests {
    public static void main(String[] args) {
        int passed = 0;
        int failed = 0;

        try {
            System.out.println("JsonTest");
            JsonTest.run();
            passed++;
        } catch (Throwable t) {
            failed++;
            t.printStackTrace();
        }

        try {
            System.out.println("FrameTest");
            FrameTest.run();
            passed++;
        } catch (Throwable t) {
            failed++;
            t.printStackTrace();
        }

        try {
            System.out.println("HandshakeTest");
            HandshakeTest.run();
            passed++;
        } catch (Throwable t) {
            failed++;
            t.printStackTrace();
        }

        try {
            System.out.println("ActionQueueTest");
            ActionQueueTest.run();
            passed++;
        } catch (Throwable t) {
            failed++;
            t.printStackTrace();
        }

        try {
            System.out.println("EventLogTest");
            EventLogTest.run();
            passed++;
        } catch (Throwable t) {
            failed++;
            t.printStackTrace();
        }

        try {
            System.out.println("StateDiffTest");
            StateDiffTest.run();
            passed++;
        } catch (Throwable t) {
            failed++;
            t.printStackTrace();
        }

        try {
            System.out.println("DispatcherTest");
            DispatcherTest.run();
            passed++;
        } catch (Throwable t) {
            failed++;
            t.printStackTrace();
        }

        try {
            System.out.println("BridgeIntegrationTest");
            BridgeIntegrationTest.run();
            passed++;
        } catch (Throwable t) {
            failed++;
            t.printStackTrace();
        }

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
