package dev.vox.lss.common.diagnostics;

import dev.vox.lss.common.config.SettingsReload;
import dev.vox.lss.common.config.SettingsSchema;
import java.util.List;

/** User-visible outcome has one status, never a success appended after failed adoption. */
public final class ClientReloadText {
    private ClientReloadText() {}
    public static String outcomeKey(SettingsReload.Status status) {
        return switch (status) {
            case APPLIED -> "lss.settings.reloaded";
            case UNCHANGED -> "lss.settings.reload_unchanged";
            case ADOPTION_PENDING -> "lss.settings.adoption_pending";
            case RECONCILIATION_FAILED -> "lss.settings.adoption_failed";
        };
    }
    public static ClientStatusText.Message normalization(SettingsSchema.Normalization normalization) {
        return new ClientStatusText.Message("lss.settings.normalized", List.of(
                clean(normalization.path()), clean(normalization.requested()), clean(normalization.effective())));
    }
    private static String clean(Object value) {
        String text = String.valueOf(value);
        var result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (result.length() >= 256) { result.append("…"); break; }
            char c = text.charAt(i);
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
                    || Character.getType(c) == Character.LINE_SEPARATOR || Character.getType(c) == Character.PARAGRAPH_SEPARATOR)
                result.append(String.format("\\u%04x", (int)c));
            else result.append(c);
        }
        return result.toString();
    }
}
