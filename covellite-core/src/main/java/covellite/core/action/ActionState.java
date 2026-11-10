package covellite.core.action;

public enum ActionState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    REJECTED
}
