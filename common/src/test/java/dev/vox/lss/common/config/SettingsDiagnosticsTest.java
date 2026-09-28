package dev.vox.lss.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SettingsDiagnosticsTest {
    @TempDir Path directory;

    @Test void serverStartupReportsAcceptedClampAndInactivePathsWithoutRewriting() throws Exception {
        Path file=directory.resolve("lss-server-config.yaml");
        String yaml="config_version: 1\ngeneration:\n  concurrency:\n    global: 999\npaper:\n  update_events: []\n";
        Files.writeString(file,yaml);
        var notices=new ArrayList<String>();
        try(var config=new YamlServerConfig(directory,false,notices::add)) {
            assertNull(config.startupError());
            assertEquals(512,config.snapshot().generation().concurrency().global());
            assertEquals(999,config.configuredSnapshot().generation().concurrency().global());
            assertTrue(notices.contains("Normalized generation.concurrency.global: requested=999, effective=512"));
            assertTrue(notices.contains("Inactive on this platform: paper.update_events"));
            assertEquals(yaml,Files.readString(file));
        }
    }

    @Test void serverFacadeRetainsCandidateMigrationBackupAndPrivateRepairReports() throws Exception {
        String original="{\"lodDistanceChunks\":128,\"lodDistanceChunksByWorld\":{\"custom:world\":50},\"farPlayersExclude\":[12345,\"Secret Player\"],\"retired key\":true}";
        Files.writeString(directory.resolve("lss-server-config.json"),original);
        Files.writeString(directory.resolve("vss-server-config.json"),"{}");
        var notices=new ArrayList<String>();
        try(var config=new YamlServerConfig(directory,true,notices::add)) {
            assertNull(config.startupError());
            String report=String.join("\n",notices);
            assertTrue(report.contains("Multiple settings candidates exist; selected "+directory.resolve("lss-server-config.json")));
            assertTrue(report.contains("Migrated "+directory.resolve("lss-server-config.json")));
            assertTrue(report.contains("backup "+directory.resolve("lss-server-config.json.migrated.bak")));
            assertTrue(report.contains("ignored keys=1"));
            assertTrue(report.contains("preserved exact world and dimension interpretations"));
            assertTrue(report.contains("Normalized far_players.excluded_players: requested=<private>, effective=<private>"));
            assertFalse(report.contains("Secret Player"));
            assertFalse(report.contains("12345"));
            assertFalse(report.contains("retired key"));
            assertEquals(original,Files.readString(directory.resolve("lss-server-config.json.migrated.bak")));
        }
    }

    @Test void reloadNamesEachNormalizedPathAndBothValuesIncludingAnUnchangedRetry() throws Exception {
        try(var config=new YamlServerConfig(directory,false,s->{})) {
            var handle=config.settingsHandle();
            var document=handle.store().read();
            handle.store().saveDraft(document.hash(),Map.of("generation.concurrency.global",999));
            for(int attempt=0;attempt<2;attempt++) {
                var outcome=config.reload(Runnable::run,(a,b,r)->CompletableFuture.completedFuture(null)).get(30,TimeUnit.SECONDS);
                var lines=ReloadFeedback.lines(config.settingsPath(),outcome);
                assertTrue(lines.contains("Normalized generation.concurrency.global: requested=999, effective=512"));
                assertTrue(lines.contains("YAML was left unchanged."));
            }
        }
    }

    @Test void diagnosticValuesCannotForgeLogLinesAndRemainBounded() {
        var lines=ReloadFeedback.normalizationLines(List.of(new SettingsSchema.Normalization("far_players.mode","bad\n\r\u001b\u202e\u2028"+"x".repeat(400),"off")));
        String line=lines.getFirst();
        assertTrue(line.contains("\\u000a\\u000d\\u001b\\u202e\\u2028"));
        assertTrue(line.contains("…"));
        assertTrue(line.length()<350);
        assertFalse(line.contains("\n"));
    }
}
