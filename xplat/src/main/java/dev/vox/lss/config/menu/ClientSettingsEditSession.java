package dev.vox.lss.config.menu;

import dev.vox.lss.common.config.ClientSettings;
import dev.vox.lss.common.config.SettingsSchema;
import dev.vox.lss.common.config.SettingsStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Disk draft shared by both Sodium generations. Sodium cleans its controls before
 * calling storage.save(), so this object, not Sodium's dirty flag, owns failed edits.
 * No operation here publishes settings or sends a preference packet.
 */
public final class ClientSettingsEditSession {
    public enum Outcome { CLEAN, UNSAVED, SAVED, FAILED, CONFLICT }
    private final SettingsStore<ClientSettings> store;
    private final Supplier<ClientSettings> active;
    private final Supplier<ClientSettings> accepted;
    private Map<String, Object> persisted;
    private final Map<String, Object> changes = new LinkedHashMap<>();
    private String baseHash;
    private String error;
    private Outcome outcome = Outcome.CLEAN;
    private Object screen;

    public ClientSettingsEditSession(SettingsStore<ClientSettings> store, Supplier<ClientSettings> active) {
        this(store, active, active);
    }
    public ClientSettingsEditSession(SettingsStore<ClientSettings> store, Supplier<ClientSettings> active,
                                     Supplier<ClientSettings> accepted) {
        this.store = store;
        this.active = active;
        this.accepted = accepted;
        persisted = SettingsSchema.client().defaults().values();
        readDisk();
    }

    /** A fresh open sees configured values; failed/conflicted edits survive navigation. */
    public void open(Object identity) {
        if (screen == identity) return;
        screen = identity;
        if (!changes.isEmpty()) return;
        readDisk();
    }
    private boolean readDisk() {
        try {
            var document = store.read();
            persisted = document.configured().values();
            baseHash = document.hash();
            error = null;
            outcome = Outcome.CLEAN;
            return true;
        } catch (Exception failure) {
            error = failure.getMessage();
            baseHash = null;
            outcome = Outcome.FAILED;
            return false;
        }
    }
    public Object get(String path) { return changes.containsKey(path) ? changes.get(path) : persisted.get(path); }
    public boolean bool(String path) { return (Boolean) get(path); }
    public int integer(String path) { return ((Number) get(path)).intValue(); }
    public void set(String path, Object value) {
        if (!persisted.containsKey(path)) throw new IllegalArgumentException("Unknown settings path: " + path);
        if (java.util.Objects.equals(value, persisted.get(path))) changes.remove(path);
        else changes.put(path, value);
        outcome = changes.isEmpty() ? Outcome.CLEAN : Outcome.UNSAVED;
    }
    /** Last valid disk observation, excluding staged/retained unsaved edits. No IO. */
    public ClientSettings savedSnapshot() { return ClientSettings.fromValues(persisted); }
    public Map<String, Object> changedPaths() { return Map.copyOf(changes); }
    public Outcome outcome() { return outcome; }
    public String error() { return error; }
    public boolean hasRetainedEdits() { return !changes.isEmpty(); }
    public boolean activeSharing() { return active.get().farPlayers().sharing().enabled(); }
    public boolean draftSharing() { return bool("far_players.sharing.enabled"); }
    public boolean pendingReload() {
        return !persisted.equals(accepted.get().values());
    }

    /** One changed-path transaction. Errors remain visible and never escape Sodium Apply. */
    public boolean save() {
        if (changes.isEmpty()) return outcome != Outcome.FAILED;
        if (outcome == Outcome.CONFLICT || baseHash == null) {
            outcome = Outcome.CONFLICT;
            return false;
        }
        try {
            var document = store.saveDraft(baseHash, Map.copyOf(changes));
            persisted = document.configured().values();
            baseHash = document.hash();
            changes.clear();
            error = null;
            outcome = Outcome.SAVED;
            return true;
        } catch (Exception failure) {
            error = failure.getMessage();
            // A conflict always requires an explicit reread/rebase action.
            try { outcome = store.read().hash().equals(baseHash) ? Outcome.FAILED : Outcome.CONFLICT; }
            catch (Exception unreadable) { outcome = Outcome.CONFLICT; }
            return false;
        }
    }

    /** User explicitly chose to rebase the selected edits onto the newly read document. */
    public boolean rebaseAndSave() {
        var selected = new LinkedHashMap<>(changes);
        if (!readDisk()) return false;
        changes.clear();
        selected.forEach(this::set);
        return save();
    }
    public void discard() { changes.clear(); readDisk(); }
    public void onReload() {
        if (!changes.isEmpty()) {
            try { if (!store.read().hash().equals(baseHash)) outcome = Outcome.CONFLICT; }
            catch (Exception failure) { outcome = Outcome.CONFLICT; error = failure.getMessage(); }
        } else readDisk();
    }
}
