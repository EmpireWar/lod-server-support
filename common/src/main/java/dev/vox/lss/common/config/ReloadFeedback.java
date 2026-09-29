package dev.vox.lss.common.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reports schema paths and sanitized normalization values; private values arrive redacted. */
public final class ReloadFeedback {
    public static List<String> lines(Path file, SettingsReload.Outcome<?> outcome) {
        var lines = new ArrayList<String>();
        String name = file == null ? "settings" : file.getFileName().toString();
        var change = outcome.commit();
        lines.add(switch (outcome.status()) {
            case APPLIED -> "Reloaded " + name + ": " + change.changedPaths().size() + " active setting(s) applied.";
            case UNCHANGED -> "Reloaded " + name + ": no active changes.";
            case ADOPTION_PENDING -> "Accepted " + name + "; subsystem adoption pending: " + String.join(", ", change.changedPaths());
            case RECONCILIATION_FAILED -> outcome.detail();
        });
        if (!change.pendingRestart().isEmpty()) lines.add("Restart required: " + String.join(", ", change.pendingRestart()));
        if (!change.pendingReconnect().isEmpty()) lines.add("Reconnect required: " + String.join(", ", change.pendingReconnect()));
        if (!change.inactivePaths().isEmpty()) lines.add("Inactive on this platform: " + String.join(", ", change.inactivePaths()));
        if (!change.normalizations().isEmpty()) {
            lines.addAll(normalizationLines(change.normalizations()));
            lines.add("YAML was left unchanged.");
        }
        if (change.changedPaths().stream().anyMatch(path -> path.startsWith("lod.distance.") || path.equals("generation.enabled")))
            lines.add("Existing legacy clients may need to reconnect for the new distance or generation policy.");
        return List.copyOf(lines);
    }
    public static List<String> normalizationLines(List<SettingsSchema.Normalization> normalizations) {
        return normalizations.stream().map(n -> "Normalized " + diagnosticValue(n.path())
                + ": requested=" + diagnosticValue(n.requested()) + ", effective=" + diagnosticValue(n.effective())).toList();
    }
    private static String diagnosticValue(Object value) {
        String text = String.valueOf(value);
        var result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (result.length() >= 256) { result.append("…"); break; }
            char c = text.charAt(i);
            // Keep each operator notice on one line, including Unicode line controls.
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
                    || Character.getType(c) == Character.LINE_SEPARATOR || Character.getType(c) == Character.PARAGRAPH_SEPARATOR)
                result.append(String.format("\\u%04x", (int)c));
            else result.append(c);
        }
        return result.toString();
    }
    private ReloadFeedback() {}
}
