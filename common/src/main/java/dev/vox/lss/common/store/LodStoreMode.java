package dev.vox.lss.common.store;

import java.util.Locale;

/** Legacy migration and runtime diagnostic tokens. YAML uses storage.lod_store.enabled. */
public enum LodStoreMode {
    OFF, FULL;

    public static LodStoreMode normalize(String value) {
        if (value == null) return OFF;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            // NOTE: no "memory" case — see the MEMORY javadoc. A config that still says
            // "memory" migrates to the disabled YAML value.
            case "full", "on" -> FULL;
            default -> OFF;
        };
    }

    /** The canonical config-file spelling ({@code FULL} writes back as {@code "on"} —
     *  the 2026-08-08 rework's user-facing name; {@code "full"} remains a read alias). */
    public String configValue() {
        return this == FULL ? "on" : name().toLowerCase(Locale.ROOT);
    }
}
