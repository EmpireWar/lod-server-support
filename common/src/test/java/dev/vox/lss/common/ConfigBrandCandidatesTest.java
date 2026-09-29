package dev.vox.lss.common;

import dev.vox.lss.common.config.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigBrandCandidatesTest {
    @TempDir Path directory;
    @AfterEach void resetBrand() { Brand.apply("LSS", "LOD Server Support", "lss", "lsslod"); }
    @Test void vssAdoptsLssYamlAndNeverForksAnotherFile() throws Exception {
        var original = new SettingsStore<>(directory, "lss", SettingsSchema.client());
        original.initialize();
        Brand.apply("VSS", "Voxy Server Side", "vss", "vsslod");
        var adopted = new SettingsStore<>(directory, Brand.lowerShortName(), SettingsSchema.client());
        adopted.initialize();
        assertEquals(original.path(), adopted.path());
        assertFalse(Files.exists(directory.resolve("vss-client-config.yaml")));
        var doc = adopted.read();
        adopted.saveDraft(doc.hash(), java.util.Map.of("far_players.sharing.enabled", false));
        assertFalse(original.read().normalized().farPlayers().sharing().enabled());
    }
    @Test void runningBrandWinsWhenBothYamlsExist() throws Exception {
        Files.writeString(directory.resolve("lss-server-config.yaml"), "config_version: 1\nlod:\n  distance:\n    default_chunks: 64\n");
        Files.writeString(directory.resolve("vss-server-config.yaml"), "config_version: 1\nlod:\n  distance:\n    default_chunks: 128\n");
        for (String brand : new String[]{"lss", "vss"}) {
            var store = new SettingsStore<>(directory, brand, SettingsSchema.server(false));
            assertEquals(brand.equals("lss") ? 64 : 128, store.initialize().normalized().lod().distance().defaultChunks());
            assertEquals(brand + "-server-config.yaml", store.path().getFileName().toString());
        }
    }
    @Test void crossBrandJsonMigrationKeepsTheSourceStemAndExactOriginal() throws Exception {
        String json = "{\"lodDistanceChunks\":32,\"enableChunkGeneration\":false}";
        Files.writeString(directory.resolve("lss-server-config.json"), json);
        var store = new SettingsStore<>(directory, "vss", SettingsSchema.server(false));
        assertFalse(store.initialize().normalized().generation().enabled());
        assertEquals("lss-server-config.yaml", store.path().getFileName().toString());
        assertEquals(json, Files.readString(directory.resolve("lss-server-config.json")));
        assertFalse(Files.exists(directory.resolve("vss-server-config.yaml")));
    }
}
