package covellite.core.action;

/**
 * Result of one tick of an action.
 */
public enum Step {
    /**
     * Action should continue running on the next pump.
     */
    CONTINUE,

    /**
     * Action completed successfully.
     */
    DONE,

    /**
     * Action failed; message may be recorded separately.
     */
    FAIL
}
