package covellite.core.action;

public class ActionRejectedException extends Exception {
    private final String reason;

    public ActionRejectedException(String reason) {
        super("Action rejected: " + reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
