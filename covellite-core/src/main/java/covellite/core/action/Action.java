package covellite.core.action;

/**
 * A long-running action that executes across multiple pumps (ticks).
 */
public interface Action {
    /**
     * Unique type name used in logs and tool schemas.
     */
    String type();

    /**
     * Called once when the action reaches the front of the queue.
     * Return DONE if it finishes immediately, CONTINUE to keep running,
     * or FAIL to mark it failed.
     */
    Step start();

    /**
     * Called once per pump while the action is RUNNING.
     */
    Step tick();

    /**
     * Called when the action terminates for any reason (success, failure,
     * cancellation or timeout). Use this to release resources.
     */
    default void stop() {}
}
