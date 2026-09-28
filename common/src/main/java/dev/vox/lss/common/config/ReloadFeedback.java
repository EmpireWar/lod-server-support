package dev.vox.lss.common.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reports paths and counts, never private aliases, player lists or arbitrary document values. */
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
        if (!change.normalizations().isEmpty()) lines.add(change.normalizations().size() + " value(s) normalized; YAML was left unchanged.");
        if (change.changedPaths().stream().anyMatch(path -> path.startsWith("lod.distance.") || path.equals("generation.enabled")))
            lines.add("Existing legacy clients may need to reconnect for the new distance or generation policy.");
        return List.copyOf(lines);
    }
    private ReloadFeedback() {}
}
