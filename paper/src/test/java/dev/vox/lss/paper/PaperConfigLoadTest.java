package dev.vox.lss.paper;

import dev.vox.lss.common.config.SettingsReload;
import dev.vox.lss.common.tracking.DirtyColumnTracker;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the plugin's real startup entry point, adoption, migration and explicit reload. */
class PaperConfigLoadTest {
    private static final String YAML = "lss-server-config.yaml";
    private static final String JSON = "lss-server-config.json";
    private static final List<String> DEFAULT_EVENTS = new PaperConfig().updateEvents();

    @Test
    void missingFileCreatesCommentedYamlWithDefaultsThatRoundTrip(@TempDir Path tempDir) throws Exception {
        Path directory = tempDir.resolve("LodServerSupport");
        try (var config = PaperConfig.load(directory)) {
            assertNull(config.startupError());
            assertEquals(512, config.lodDistanceChunks());
            assertFalse(DEFAULT_EVENTS.isEmpty());
            assertEquals(DEFAULT_EVENTS, config.updateEvents());
            assertEquals("on", config.lodStore());
            assertEquals(directory.resolve(YAML).toAbsolutePath(), config.settingsPath());
            String saved = Files.readString(config.settingsPath());
            assertTrue(saved.contains("#"));
            assertTrue(saved.contains("update_events:"));
            assertFalse(Files.exists(directory.resolve(JSON)));
            try (var reread = PaperConfig.load(directory)) {
                assertEquals(config.snapshot(), reread.snapshot());
                assertEquals(saved, Files.readString(config.settingsPath()), "ordinary startup must not rewrite YAML");
            }
        }
    }

    @Test
    void partialLegacyFileMigratesWithoutArmingStoreAndPinsOldVanillaRadii(@TempDir Path directory) throws Exception {
        String original = "{\"lodDistanceChunks\": 64}";
        Files.writeString(directory.resolve(JSON), original);
        try (var config = PaperConfig.load(directory)) {
            assertNull(config.startupError());
            assertEquals(64, config.lodDistanceChunks());
            assertEquals("off", config.lodStore());
            assertEquals(java.util.Map.of("minecraft:overworld",64,"minecraft:the_nether",64,"minecraft:the_end",64),config.snapshot().lod().distance().byDimension());
            assertEquals(DEFAULT_EVENTS, config.updateEvents());
            assertTrue(config.enabled());
            assertEquals(25.0, config.mbPerSecondLimitPerPlayer());
            assertEquals(26_214_400, config.bytesPerSecondPerPlayer());
            assertEquals(75.0, config.mbPerSecondLimitGlobal());
            assertEquals(78_643_200, config.bytesPerSecondGlobal());
            assertEquals(0, config.diskReaderThreads());
            assertEquals(1024, config.sendQueueLimitPerPlayer());
            assertTrue(config.enableChunkGeneration());
            assertEquals(40, config.generationConcurrencyLimitGlobal());
            assertEquals(40, config.generationConcurrencyLimitPerPlayer());
            assertEquals(1200, config.generationTimeoutTicks());
            assertEquals(200, config.dirtyBroadcastIntervalTicks());
            assertEquals(0, config.perDimensionTimestampCacheSizeMB());
            assertEquals(original, Files.readString(directory.resolve(JSON)));
            assertEquals(original, Files.readString(directory.resolve(JSON + ".migrated.bak")));
            try (var reread = PaperConfig.load(directory)) {
                assertEquals(config.snapshot(), reread.snapshot());
            }
        }
    }

    @Test
    void brokenLegacyFilesDisableServiceAndPreserveSourceWithoutCreatingYaml(@TempDir Path directory) throws Exception {
        for (String broken : List.of("", "{\"lodDistanceChunks\": 64", "{\"updateEvents\": \"wrong\", \"lodDistanceChunks\": 64}")) {
            Files.writeString(directory.resolve(JSON), broken);
            try (var config = assertDoesNotThrow(() -> PaperConfig.load(directory))) {
                assertNotNull(config.startupError());
                assertFalse(config.enabled());
                assertFalse(config.enableChunkGeneration());
                assertEquals("off", config.lodStore());
                assertEquals(broken, Files.readString(directory.resolve(JSON)));
                assertFalse(Files.exists(directory.resolve(YAML)));
            }
        }
    }

    @Test
    void authoritativeInvalidYamlNeverFallsBackToValidJson(@TempDir Path directory) throws Exception {
        String yaml = "config_version: 1\nlod:\n  distance:\n    defaut_chunks: 64\n";
        Files.writeString(directory.resolve(YAML), yaml);
        Files.writeString(directory.resolve(JSON), "{\"lodDistanceChunks\": 64}");
        try (var config = PaperConfig.load(directory)) {
            assertNotNull(config.startupError());
            assertFalse(config.enabled());
            assertEquals(yaml, Files.readString(directory.resolve(YAML)));
            assertFalse(Files.exists(directory.resolve(JSON + ".migrated.bak")));
        }
    }

    @Test
    void legacyEventsReplaceDefaultsAndNullListMigratesToEmpty(@TempDir Path directory) throws Exception {
        for (String value : List.of("[\"org.bukkit.event.block.BlockPlaceEvent\"]", "null")) {
            Files.deleteIfExists(directory.resolve(YAML));
            String original = "{\"updateEvents\": " + value + "}";
            Files.writeString(directory.resolve(JSON), original);
            try (var config = PaperConfig.load(directory)) {
                assertNull(config.startupError());
                assertEquals(value.equals("null") ? List.of() : List.of("org.bukkit.event.block.BlockPlaceEvent"), config.updateEvents());
                assertEquals(original, Files.readString(directory.resolve(JSON)));
                try (var reread = PaperConfig.load(directory)) {
                    assertEquals(config.updateEvents(), reread.updateEvents());
                }
            }
        }
    }

    @Test
    void migrationDropsNullEventEntryAndRegistersBothValidNeighbours(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve(JSON), "{\"updateEvents\": ["
                + "\"org.bukkit.event.block.BlockPlaceEvent\", null, \"org.bukkit.event.block.BlockBreakEvent\"]}");
        try (var config = PaperConfig.load(directory)) {
            assertNull(config.startupError());
            List<String> expected = List.of("org.bukkit.event.block.BlockPlaceEvent", "org.bukkit.event.block.BlockBreakEvent");
            assertEquals(expected, config.updateEvents());
            List<Class<?>> registered = new ArrayList<>();
            new PaperWorldHandler(recordingPlugin(registered), new DirtyColumnTracker()).registerUpdateListeners(config.updateEvents());
            assertEquals(expected, registered.stream().map(Class::getName).toList());
        }
    }

    @Test
    void freshDefaultEventsAllRegister(@TempDir Path directory) {
        try (var config = PaperConfig.load(directory)) {
            List<Class<?>> registered = new ArrayList<>();
            new PaperWorldHandler(recordingPlugin(registered), new DirtyColumnTracker()).registerUpdateListeners(config.updateEvents());
            assertEquals(DEFAULT_EVENTS, registered.stream().map(Class::getName).toList());
        }
    }

    @Test
    void adoptsOtherBrandYamlAndReloadsOnlyOnExplicitRequestWithoutRewriting(@TempDir Path directory) throws Exception {
        Path adopted = directory.resolve("vss-server-config.yaml");
        String initial = "# retained operator comment\nconfig_version: 1\nlod:\n  distance:\n    default_chunks: 64\n";
        Files.writeString(adopted, initial);
        try (var config = PaperConfig.load(directory)) {
            assertNull(config.startupError());
            assertEquals(adopted.toAbsolutePath(), config.settingsPath());
            assertEquals(64, config.lodDistanceChunks());
            assertEquals(initial, Files.readString(adopted));
            String edited = initial.replace("64", "96");
            Files.writeString(adopted, edited);
            assertEquals(64, config.lodDistanceChunks(), "disk edits are drafts until explicit reload");
            var result = config.reload(Runnable::run, (previous, next, revision) -> CompletableFuture.completedFuture(null))
                    .get(10, TimeUnit.SECONDS);
            assertEquals(SettingsReload.Status.APPLIED, result.status());
            assertEquals(96, config.lodDistanceChunks());
            assertEquals(edited, Files.readString(adopted));
            assertFalse(Files.exists(directory.resolve(YAML)));
        }
    }

    @Test
    void adoptsOtherBrandLegacyStemAndRetainsByteExactBackup(@TempDir Path directory) throws Exception {
        String original = "{\"lodDistanceChunks\": 80, \"generationTimeoutSeconds\": 3, \"dirtyBroadcastIntervalSeconds\": 0}";
        Path source = directory.resolve("vss-server-config.json");
        Files.writeString(source, original);
        try (var config = PaperConfig.load(directory)) {
            assertNull(config.startupError());
            assertEquals(directory.resolve("vss-server-config.yaml").toAbsolutePath(), config.settingsPath());
            assertEquals(80, config.lodDistanceChunks());
            assertEquals(60, config.generationTimeoutTicks());
            assertEquals(0, config.dirtyBroadcastIntervalTicks());
            assertEquals(original, Files.readString(source));
            assertEquals(original, Files.readString(directory.resolve("vss-server-config.json.migrated.bak")));
            assertFalse(Files.exists(directory.resolve(YAML)));
        }
    }

    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return iface.cast(Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler));
    }

    private static Plugin recordingPlugin(List<Class<?>> registered) {
        PluginManager manager = proxy(PluginManager.class, (p, method, args) -> {
            if ("registerEvent".equals(method.getName())) registered.add((Class<?>) args[0]);
            return null;
        });
        Server server = proxy(Server.class, (p, method, args) -> "getPluginManager".equals(method.getName()) ? manager : null);
        return proxy(Plugin.class, (p, method, args) -> "getServer".equals(method.getName()) ? server : null);
    }
}
