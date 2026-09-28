package dev.vox.lss.networking.client;

import dev.vox.lss.common.farplayers.FarPlayerWire;
import java.util.function.Function;

/** Client-owner preference delivery survives reception retirement, scoped to one transport. */
final class FarPlayerPreferenceDelivery {
    enum Outcome { SENT, NO_CHANNEL, FAILED }
    private Object connection;
    private boolean ready;
    private FarPlayerWire.Prefs sent;
    private FarPlayerWire.Prefs pending;
    private int ticks;
    private Outcome outcome = Outcome.NO_CHANNEL;

    void sessionReady(Object identity) {
        if (connection != identity) clear();
        connection = identity;
        ready = identity != null;
    }
    void handshake() { ready = false; sent = null; }
    Outcome accept(Object identity, FarPlayerWire.Prefs desired,
                   Function<FarPlayerWire.Prefs, Outcome> enqueue) {
        if (identity != connection || !ready) {
            outcome = Outcome.NO_CHANNEL;
            return outcome;
        }
        if (desired.equals(sent)) {
            pending = null;
            return outcome = Outcome.SENT;
        }
        pending = desired;
        return attempt(enqueue);
    }
    private Outcome attempt(Function<FarPlayerWire.Prefs, Outcome> enqueue) {
        try { outcome = enqueue.apply(pending); }
        catch (RuntimeException failure) { outcome = Outcome.FAILED; }
        if (outcome == Outcome.SENT) { sent = pending; pending = null; }
        return outcome;
    }
    void tick(Object identity, Function<FarPlayerWire.Prefs, Outcome> enqueue) {
        if (connection != identity) { clear(); return; }
        if (!ready || pending == null) return;
        if (++ticks >= 20) { ticks = 0; attempt(enqueue); }
    }
    Outcome outcome() { return outcome; }
    boolean pending() { return pending != null; }
    void invalidateSent() { sent = null; }
    void clear() {
        connection = null;
        ready = false;
        sent = null;
        pending = null;
        ticks = 0;
        outcome = Outcome.NO_CHANNEL;
    }
}
