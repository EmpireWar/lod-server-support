package dev.vox.lss.common.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerStatusSnapshotTest {
    @Test void generationAdmissionAndConfiguredValueDifferWithoutInventingRestartTiming() {
        for (boolean admission : new boolean[]{false, true}) {
            var snapshot = new ServerStatusSnapshot(1, 1, true, true, admission, !admission,
                    23, 0, 0, 0, 0, 0, DiagnosticVersions.unknown());
            String text = String.join("\n", snapshot.lines());
            assertTrue(text.contains("generation admission: " + admission));
            assertTrue(text.contains("Generation configured: " + !admission));
            assertFalse(text.toLowerCase(java.util.Locale.ROOT).contains("restart"));
            var json = new com.google.gson.Gson().toJsonTree(snapshot).getAsJsonObject();
            assertEquals(1, json.get("schemaVersion").getAsInt());
            assertEquals(admission, json.get("generationEnabled").getAsBoolean());
            assertEquals(!admission, json.get("generationConfiguredForRestart").getAsBoolean(),
                    "schema-1 legacy field remains available with its configured-value meaning");
        }
    }
}
