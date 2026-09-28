package dev.vox.lss.common.diagnostics;

/** Non-overlapping Sodium viewport, wrapped notice area and always-available action row. */
public final class SettingsNoticeLayout {
    public record Bounds(int usableHeight, int noticeBottom, int buttonY, int buttonHeight, int physicalHeight) {}
    public static Bounds fit(int physicalHeight, int requestedNoticeHeight) {
        int total = Math.max(1, physicalHeight);
        int actionHeight = Math.min(24, total);
        int noticeBottom = total - actionHeight;
        int minimumControls = Math.min(128, noticeBottom);
        int usable = Math.max(minimumControls, noticeBottom - Math.max(0, requestedNoticeHeight));
        int buttonHeight = Math.min(20, actionHeight);
        int buttonY = noticeBottom + (actionHeight - buttonHeight) / 2;
        return new Bounds(usable, noticeBottom, buttonY, buttonHeight, total);
    }
    private SettingsNoticeLayout() {}
}
