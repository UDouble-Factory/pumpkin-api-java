package plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import pumpkin.scheduler.Scheduler;
import pumpkin.server.Server;

public final class PluginTasks {
    private static final long FIRST_ID = 0x8000_0000L;
    private final Map<Long, Entry> entries = new HashMap<>();
    private long nextId = FIRST_ID;
    private boolean closed;

    PluginTasks() {
    }

    public ScheduledTask afterTicks(long delayTicks, Consumer<Server> action) {
        Objects.requireNonNull(action);
        if (closed) throw new IllegalStateException("Cannot schedule tasks after plugin unload");
        if (nextId == 0xFFFF_FFFFL) throw new IllegalStateException("Handler IDs exhausted");
        Entry entry = new Entry(nextId++, action);
        entries.put(entry.handlerId, entry);

        try {
            entry.taskId = Scheduler.scheduleDelayedTask(entry.handlerId, delayTicks);
            entry.registered = true;
        } catch (Throwable failure) {
            entries.remove(entry.handlerId);
            entry.finished = true;
            throw failure;
        }

        if (entry.cancelled) Scheduler.cancelTask(entry.taskId);
        if (closed) cancel(entry);
        return new ScheduledTask(() -> cancel(entry));
    }

    boolean dispatch(long handlerId, Server server) {
        Entry entry = entries.remove(handlerId);
        if (entry != null) {
            entry.finished = true;
            entry.action.accept(server);
            return true;
        }
        return handlerId >= FIRST_ID && handlerId < nextId;
    }

    private void cancel(Entry entry) {
        if (entry.finished) return;
        entry.finished = true;
        entry.cancelled = true;
        entries.remove(entry.handlerId);
        if (entry.registered) Scheduler.cancelTask(entry.taskId);
    }

    void close() {
        if (closed) return;
        closed = true;
        Throwable failure = null;
        for (Entry entry : new ArrayList<>(entries.values())) {
            try {
                cancel(entry);
            } catch (Throwable error) {
                if (failure == null) {
                    failure = error;
                } else if (failure != error) {
                    failure.addSuppressed(error);
                }
            }
        }
        entries.clear();
        if (failure != null) throw new IllegalStateException("Task cleanup failed", failure);
    }

    private static final class Entry {
        private final long handlerId;
        private final Consumer<Server> action;
        private long taskId;
        private boolean registered;
        private boolean finished;
        private boolean cancelled;

        private Entry(long handlerId, Consumer<Server> action) {
            this.handlerId = handlerId;
            this.action = action;
        }
    }
}
