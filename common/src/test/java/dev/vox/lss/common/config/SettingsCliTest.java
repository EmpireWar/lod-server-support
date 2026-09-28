package dev.vox.lss.common.config;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SettingsCliTest {
    @TempDir Path dir;
    @Test void schemaIsExportedFromRuntimeDescriptors()throws Exception {
        @SuppressWarnings("unchecked") var output=(Map<String,Object>)SettingsCli.run(new String[]{"schema"});
        @SuppressWarnings("unchecked") var server=(Map<String,Object>)output.get("server");
        assertEquals(SettingsSchema.server(false).descriptors(),server.get("descriptors"));assertTrue(server.get("document").toString().contains("comparable in size"));
    }
    @Test void fixtureNamesUseSameCodecAndSingleAtomicEdit()throws Exception {
        Path file=dir.resolve("custom-scenario.yaml");SettingsCli.run(new String[]{"create","--path",file.toString()});
        SettingsCli.run(new String[]{"edit","--path",file.toString(),"--set","generation.enabled=false","--set","generation.concurrency.global=6","--set","generation.concurrency.per_player=3","--set","lod.distance.by_dimension={\"custom:planet\":888}"});
        var settings=new YamlSettingsCodec<>(SettingsSchema.server(false)).parse(Files.readAllBytes(file)).normalized();
        assertFalse(settings.generation().enabled());assertEquals(6,settings.generation().concurrency().global());assertEquals(3,settings.generation().concurrency().perPlayer());assertEquals(Map.of("custom:planet",888),settings.lod().distance().byDimension());
        assertTrue(Files.readString(file).contains("world-generation CPU"));assertDoesNotThrow(()->SettingsCli.run(new String[]{"validate","--path",file.toString()}));
    }
    @Test void cliRejectsPartialInvalidEditsWithoutWriting()throws Exception {
        Path file=dir.resolve("client.yaml");SettingsCli.run(new String[]{"create","--side","client","--path",file.toString()});byte[] original=Files.readAllBytes(file);
        assertThrows(SettingsException.class,()->SettingsCli.run(new String[]{"edit","--side","client","--path",file.toString(),"--set","lod.receive=false","--set","bad.path=3"}));assertArrayEquals(original,Files.readAllBytes(file));
    }
    @Test void migrateDirectoryUsesBrandSelectionAndSourceStem()throws Exception {
        Files.writeString(dir.resolve("vss-client-config.json"),"{\"farPlayersShareSelf\":false}");
        SettingsCli.run(new String[]{"migrate","--side","client","--path",dir.toString()});
        assertTrue(Files.exists(dir.resolve("vss-client-config.yaml")));assertFalse(Files.exists(dir.resolve("lss-client-config.yaml")));
    }
}
