package dev.vox.lss.sponge;

import dev.vox.lss.common.config.SettingsReload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the plugin's real startup entry point, adoption and explicit reload. */
class SpongeConfigLoadTest {
    private static final String YAML = "lss-server-config.yaml";

    @Test
    void missingFileCreatesCommentedVanillaTemplateThatRoundTrips(@TempDir Path tempDir) throws Exception {
        Path directory = tempDir.resolve("lodserversupport");
        try (var config = SpongeConfig.load(directory)) {
            assertNull(config.startupError());
            assertEquals(512, config.lodDistanceChunks());
            assertEquals("on", config.lodStore());
            assertEquals(directory.resolve(YAML).toAbsolutePath(), config.settingsPath());
            String saved = Files.readString(config.settingsPath());
            assertTrue(saved.contains("#"));
            // Sponge reads no Bukkit events and has no per-world-name distances
            assertFalse(saved.contains("update_events:"));
            assertFalse(saved.contains("by_world:"));
            try (var reread = SpongeConfig.load(directory)) {
                assertEquals(config.snapshot(), reread.snapshot());
                assertEquals(saved, Files.readString(config.settingsPath()), "ordinary startup must not rewrite YAML");
            }
        }
    }

    @Test
    void invalidYamlDisablesTheServiceAndIsLeftUntouched(@TempDir Path directory) throws Exception {
        String yaml = "config_version: 1\nlod:\n  distance:\n    defaut_chunks: 64\n";
        Files.writeString(directory.resolve(YAML), yaml);
        try (var config = SpongeConfig.load(directory)) {
            assertNotNull(config.startupError());
            assertFalse(config.enabled());
            assertFalse(config.enableChunkGeneration());
            assertEquals(yaml, Files.readString(directory.resolve(YAML)));
        }
    }

    @Test
    void adoptsOtherBrandYamlAndReloadsOnlyOnExplicitRequestWithoutRewriting(@TempDir Path directory) throws Exception {
        Path adopted = directory.resolve("vss-server-config.yaml");
        String initial = "# retained operator comment\nconfig_version: 1\nlod:\n  distance:\n    default_chunks: 64\n";
        Files.writeString(adopted, initial);
        try (var config = SpongeConfig.load(directory)) {
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
}
