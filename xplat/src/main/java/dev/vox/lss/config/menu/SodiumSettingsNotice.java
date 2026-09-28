package dev.vox.lss.config.menu;

import dev.vox.lss.common.Brand;
import dev.vox.lss.config.LSSClientConfig;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

/** Notices occupy their own strip below Sodium's usable height, on both generations. */
public final class SodiumSettingsNotice {
    private static final List<String> OUTCOMES = List.of("save_notice", "saved_notice", "failure_notice",
            "conflict_notice", "unsaved_notice");
    private SodiumSettingsNotice() {}
    public static List<Component> lines(Object screen) {
        var draft = LSSClientConfig.CONFIG.edits();
        String outcome = switch (draft.outcome()) {
            case FAILED -> "failure_notice";
            case CONFLICT -> "conflict_notice";
            case UNSAVED -> "unsaved_notice";
            case SAVED -> "saved_notice";
            case CLEAN -> draft.pendingReload() ? "saved_notice" : "save_notice";
        };
        var lines = new ArrayList<Component>();
        lines.add(notice(outcome));
        lines.add(sharing(draft.activeSharing(), SodiumDraftRefresh.previewSharing(screen)));
        if (!LSSClientConfig.CONFIG.pendingReconnect().isEmpty())
            lines.add(Component.translatable("lss.settings.pending_reconnect_notice"));
        return List.copyOf(lines);
    }
    /** Reserve the largest translated outcome so Apply never moves controls under the mouse. */
    public static int reservedHeight(Font font, int width) {
        int textWidth = textWidth(width);
        int outcome = OUTCOMES.stream().mapToInt(key -> font.split(notice(key), textWidth).size()).max().orElse(1);
        int sharing = 1;
        for (boolean active : new boolean[]{false, true}) for (boolean draft : new boolean[]{false, true})
            sharing = Math.max(sharing, font.split(sharing(active, draft), textWidth).size());
        int reconnect = font.split(Component.translatable("lss.settings.pending_reconnect_notice"), textWidth).size();
        return 8 + 11 * (outcome + sharing + reconnect);
    }
    public static int textWidth(int width) { return Math.max(80, width - 16); }
    private static Component notice(String key) {
        return Component.translatable("lss.settings." + key, Brand.clientCommand());
    }
    private static Component sharing(boolean active, boolean draft) {
        return Component.translatable("lss.settings.sharing_state", onOff(active), onOff(draft));
    }
    private static Component onOff(boolean value) {
        return Component.translatable(value ? "options.on" : "options.off");
    }
}
