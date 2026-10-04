package plugin;

public final class ScheduledTask {
    private final Runnable cancelAction;

    ScheduledTask(Runnable cancelAction) {
        this.cancelAction = cancelAction;
    }

    public void cancel() {
        cancelAction.run();
    }

}
