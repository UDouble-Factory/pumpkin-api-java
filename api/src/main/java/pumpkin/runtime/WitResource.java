package pumpkin.runtime;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;

public abstract class WitResource implements AutoCloseable {
    private static final ReferenceQueue<WitResource> QUEUE = new ReferenceQueue<>();
    private static final HashSet<HandleReference> LIVE = new HashSet<>();
    private final int handle;
    private final boolean owned;
    private final HandleReference reference;
    private boolean closed;

    protected WitResource(int type, int handle, boolean owned) {
        this.handle = handle;
        this.owned = owned;
        reference = owned ? new HandleReference(this, type, handle) : null;
        if (reference != null) LIVE.add(reference);
    }

    final int handle() {
        checkOpen();
        return handle;
    }

    final void checkOpen() {
        if (closed || reference != null && !reference.live) throw new IllegalStateException("Resource is closed or its ownership was transferred");
    }

    final void checkOwned() {
        if (!owned) throw new IllegalStateException("Cannot transfer a borrowed resource");
    }

    final void move() {
        checkOpen();
        checkOwned();
        closed = true;
        reference.live = false;
        LIVE.remove(reference);
        reference.clear();
    }

    @Override
    public final void close() {
        if (closed) return;
        closed = true;
        if (reference != null) release(reference);
    }

    static void collect() {
        HandleReference reference;
        while ((reference = (HandleReference) QUEUE.poll()) != null) {
            release(reference);
        }
    }

    public static void closeAll() {
        for (HandleReference reference : new ArrayList<>(LIVE)) {
            release(reference);
        }
    }

    private static void release(HandleReference reference) {
        if (!reference.live) return;
        reference.live = false;
        LIVE.remove(reference);
        reference.clear();
        Bridge.drop(reference.type, reference.handle);
    }

    private static final class HandleReference extends WeakReference<WitResource> {
        private final int type;
        private final int handle;
        private boolean live = true;

        private HandleReference(WitResource resource, int type, int handle) {
            super(resource, QUEUE);
            this.type = type;
            this.handle = handle;
        }
    }
}
