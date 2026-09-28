package dev.vox.lss.common.diagnostics;

/** Keeps Sodium's controls usable even at unusually small GUI sizes or large fonts. */
public final class SettingsNoticeLayout {
    public record Bounds(int usableHeight, int physicalHeight) {}
    public static Bounds fit(int physicalHeight, int requestedNoticeHeight) {
        int total = Math.max(1, physicalHeight);
        int minimumControls = Math.min(128, total);
        int usable = Math.max(minimumControls, total - Math.max(0, requestedNoticeHeight));
        return new Bounds(usable, total);
    }
    private SettingsNoticeLayout() {}
}
