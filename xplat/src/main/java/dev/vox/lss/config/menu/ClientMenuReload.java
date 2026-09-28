package dev.vox.lss.config.menu;

import java.util.function.BooleanSupplier;

/** Client-owner queue: repeated Apply clicks retain one reload of the latest disk state. */
public final class ClientMenuReload {
    private final BooleanSupplier busy;
    private final Runnable reload;
    private boolean pending;

    public ClientMenuReload(BooleanSupplier busy, Runnable reload) {
        this.busy = busy;
        this.reload = reload;
    }

    public void request() {
        pending = true;
        drain();
    }

    /** Called after every command/menu reload completes, including failed attempts. */
    public void drain() {
        if (!pending || busy.getAsBoolean()) return;
        pending = false;
        reload.run();
    }
}
