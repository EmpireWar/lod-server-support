package dev.vox.lss.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Legacy JSON exists only as migration input; startup never rewrites existing source files. */
class JsonConfigLoadTest {
    @TempDir Path directory;
    @Test void freshCreatesCommentedYamlAndNormalReadPreservesBytes() throws Exception {
        var c = new LSSServerConfig(directory);
        assertNull(c.startupError());
        Path file = directory.resolve("lss-server-config.yaml");
        String yaml = Files.readString(file);
        assertTrue(yaml.contains("Tradeoff:"));
        assertEquals(64, c.lodDistanceForWorld("minecraft:the_nether"));
        new LSSServerConfig(directory);
        assertEquals(yaml, Files.readString(file));
        assertFalse(Files.exists(directory.resolve("lss-server-config.json")));
    }
    @Test void legacyGlobalDefaultPreservedAndAbsentStoreRemainsOff() throws Exception {
        String legacy = "{\"lodDistanceChunks\":96,\"generationTimeoutSeconds\":3,\"dirtyBroadcastIntervalSeconds\":0}";
        Path json = directory.resolve("lss-server-config.json");
        Files.writeString(json, legacy);
        var c = new LSSServerConfig(directory);
        assertNull(c.startupError());
        assertEquals(96, c.lodDistanceForWorld("minecraft:the_nether"));
        assertEquals("off", c.lodStore());
        assertEquals(60, c.generationTimeoutTicks());
        assertEquals(0, c.dirtyBroadcastIntervalTicks());
        assertEquals(legacy, Files.readString(json));
        assertEquals(legacy, Files.readString(directory.resolve("lss-server-config.json.migrated.bak")));
    }
    @Test void corruptYamlIsAuthoritativeAndCannotEnableDefaultsOrFallBackToJson() throws Exception {
        String bad = "config_version: 1\nservice: [broken\n";
        Files.writeString(directory.resolve("lss-server-config.yaml"), bad);
        Files.writeString(directory.resolve("lss-server-config.json"), "{\"enabled\":true}");
        var c = assertDoesNotThrow(() -> new LSSServerConfig(directory));
        assertNotNull(c.startupError());
        assertFalse(c.enabled());
        assertFalse(c.enableChunkGeneration());
        assertEquals("off", c.lodStore());
        assertEquals(bad, Files.readString(directory.resolve("lss-server-config.yaml")));
    }
    @Test void malformedLegacyPreservedAndRepairRequiresRestart() throws Exception {
        for (String bad : new String[]{"", "null", "{", "{\"enabled\":\"yes\"}"}) {
            Path dir = Files.createTempDirectory(directory, "bad-");
            Files.writeString(dir.resolve("lss-server-config.json"), bad);
            var c = new LSSServerConfig(dir);
            assertFalse(c.enabled());
            assertNotNull(c.startupError());
            assertEquals(bad, Files.readString(dir.resolve("lss-server-config.json")));
            assertFalse(Files.exists(dir.resolve("lss-server-config.yaml")));
        }
    }
}
