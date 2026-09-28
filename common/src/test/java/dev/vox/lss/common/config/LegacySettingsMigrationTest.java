package dev.vox.lss.common.config;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LegacySettingsMigrationTest {
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private static ServerSettings server(String json){return LegacySettingsMigration.migrate(bytes(json),SettingsSchema.server(false)).settings();}
    private static ClientSettings client(String json){return LegacySettingsMigration.migrate(bytes(json),SettingsSchema.client()).settings();}
    @Test void upgradeNeverArmsStoreOrNewNetherRadius(){var s=server("{}");assertFalse(s.storage().lodStore().enabled());assertTrue(s.lod().distance().byDimension().isEmpty());assertEquals(512,s.lod().distance().defaultChunks());assertTrue(SettingsSchema.server(false).defaults().storage().lodStore().enabled());}
    @ParameterizedTest @CsvSource({"1,20","60,1200","600,12000","0,20","-3,20","999,12000"})
    void generationSecondsBecomeTicksAfterOldClamp(int old,int ticks){assertEquals(ticks,server("{\"generationTimeoutSeconds\":"+old+"}").generation().timeoutTicks());}
    @ParameterizedTest @CsvSource({"0,0","1,20","10,200","300,6000","-9,0","999,6000"})
    void broadcastSecondsBecomeTicksAfterOldClamp(int old,int ticks){assertEquals(ticks,server("{\"dirtyBroadcastIntervalSeconds\":"+old+"}").updates().dirtyBroadcastIntervalTicks());}
    @Test void missingAndNullTickSettingsKeepOldDefaults(){for(String json:List.of("{}","{\"generationTimeoutSeconds\":null,\"dirtyBroadcastIntervalSeconds\":null}")){assertEquals(1200,server(json).generation().timeoutTicks());assertEquals(200,server(json).updates().dirtyBroadcastIntervalTicks());}}
    @ParameterizedTest @CsvSource({"on,true","full,true","off,false","memory,false","garbage,false"})
    void storeModeHistory(String mode,boolean enabled){assertEquals(enabled,server("{\"lodStore\":\""+mode+"\"}").storage().lodStore().enabled());}
    @Test void newerBandwidthWinsOnlyWhenNonnegative(){
        assertEquals(9,server("{\"mbPerSecondLimitPerPlayer\":9,\"bytesPerSecondLimitPerPlayer\":1048576}").network().bandwidth().perPlayerMibPerSecond());
        assertEquals(2,server("{\"mbPerSecondLimitPerPlayer\":-1,\"bytesPerSecondLimitPerPlayer\":2097152}").network().bandwidth().perPlayerMibPerSecond());
        assertEquals(25,server("{\"mbPerSecondLimitPerPlayer\":-1,\"bytesPerSecondLimitPerPlayer\":-1}").network().bandwidth().perPlayerMibPerSecond());
        assertEquals(1d/1024,server("{\"mbPerSecondLimitPerPlayer\":0}").network().bandwidth().perPlayerMibPerSecond());
    }
    @Test void oldScalarAndExactWorldNamesKeepTheirOldMeaning(){
        String json="{\"lodDistanceChunks\":777,\"lodDistanceChunksByWorld\":{\"the_nether\":81,\"minecraft:the_nether\":99,\" Minecraft:BAD \":70,\" 世界 空間 \":40,\" custom:space \":44}}";
        var mod=server(json);assertEquals(777,mod.lod().distance().defaultChunks());
        assertEquals(Map.of("minecraft:the_nether",99,"custom:space",44),mod.lod().distance().byDimension());assertTrue(mod.lod().distance().byWorld().isEmpty());
        var paper=LegacySettingsMigration.migrate(bytes(json),SettingsSchema.server(true)).settings();
        assertEquals(81,paper.lod().distance().byWorld().get("the_nether"));assertEquals(99,paper.lod().distance().byWorld().get("minecraft:the_nether"));
        assertEquals(40,paper.lod().distance().byWorld().get("世界 空間"));assertEquals(mod.lod().distance().byDimension(),paper.lod().distance().byDimension());
    }
    @Test void migrationPreservesEmptyCollectionsAndPrivacyOptouts(){
        var client=client("{\"farPlayersShareSelf\":false,\"receiveServerLods\":false,\"cacheAddressAliases\":[[\"Exact.EXAMPLE\",\"世界\"]],\"crossVersionBlockFallbacks\":{\"unknown:block\":\"minecraft:stone\",\"bad\":null}} ");
        assertFalse(client.farPlayers().sharing().enabled());assertFalse(client.lod().receive());
        assertEquals(List.of(List.of("Exact.EXAMPLE","世界")),client.cache().addressAliases());assertEquals(1,client.compatibility().blockFallbacks().overrides().size());
        assertTrue(server("{\"xrayHiddenBlocks\":[]}").privacy().xray().hiddenBlocks().isEmpty());
        assertFalse(server("{\"xrayHiddenBlocks\":null}").privacy().xray().hiddenBlocks().isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"opt-in"," optin ","OPT_IN"})
    void modeAliasesNormalizeExplicitly(String mode){assertEquals("opt_in",server("{\"farPlayers\":\""+mode+"\"}").farPlayers().mode());}
    @Test void unknownAndNullModesStayPrivacySafe(){for(String value:List.of("null","\"unknown\"")){var s=server("{\"farPlayers\":"+value+",\"xrayObfuscation\":"+value+"}");assertEquals("off",s.farPlayers().mode());assertEquals("auto",s.privacy().xray().mode());}}
    @Test void nullPrimitiveKeepsNeighborAndMissingDefault(){var s=server("{\"enableChunkGeneration\":null,\"farPlayersSendSpectators\":true}");assertTrue(s.generation().enabled());assertTrue(s.farPlayers().sendSpectators());}
    @Test void paperNullEventsDifferFromMissing(){var schema=SettingsSchema.server(true);assertFalse(LegacySettingsMigration.migrate(bytes("{}"),schema).settings().paper().updateEvents().isEmpty());for(String value:List.of("null","[]"))assertTrue(LegacySettingsMigration.migrate(bytes("{\"updateEvents\":"+value+"}"),schema).settings().paper().updateEvents().isEmpty());assertEquals(List.of("custom.Event"),LegacySettingsMigration.migrate(bytes("{\"updateEvents\":[null,\"custom.Event\"]}"),schema).settings().paper().updateEvents());}
    @Test void explicitLegacyQuotedPrimitiveCompatibility(){var s=server("{\"enabled\":\"FALSE\",\"lodDistanceChunks\":\"123.0\",\"mbPerSecondLimitGlobal\":\"4.25\"}");assertFalse(s.service().enabled());assertEquals(123,s.lod().distance().defaultChunks());assertEquals(4.25,s.network().bandwidth().globalMibPerSecond());}
    @Test void malformedAliasGroupsDropWithoutTouchingGoodSpelling(){var c=client("{\"cacheAddressAliases\":[[\"Keep.CASE\",\"second\"],null,[\"bad\",null],7],\"unknownBlockFallback\":\"   \"}");assertEquals(List.of(List.of("Keep.CASE","second")),c.cache().addressAliases());assertEquals("minecraft:stone",c.compatibility().blockFallbacks().defaultBlock());}
    @Test void ignoredKeysReportedByCountOnly(){var r=LegacySettingsMigration.migrate(bytes("{\"private unknown\":1,\"another\":false}"),SettingsSchema.server(false));assertEquals(2,r.ignoredKeys());assertTrue(r.warnings().stream().noneMatch(w->w.contains("private unknown")));}
    @ParameterizedTest @ValueSource(strings={"", "[]", "null", "{bad:1}", "{\"enabled\":true,\"enabled\":false}", "{\"enabled\":\"yes\"}", "{\"lodDistanceChunks\":\"1.5\"}", "{\"lodDistanceChunks\":2147483648}", "{\"mbPerSecondLimitGlobal\":\"NaN\"}", "{} {}", "// comment\n{}"})
    void rejectsCorruptOrUnsupportedJson(String json){assertThrows(SettingsException.class,()->server(json));}
    @Test void streamBudgetsApplyBeforeJsonTreeAllocation(){
        assertDoesNotThrow(()->LegacySettingsMigration.readJson(bytes("{\"x\":"+"[".repeat(31)+"0"+"]".repeat(31)+"}")));
        assertThrows(SettingsException.class,()->LegacySettingsMigration.readJson(bytes("{\"x\":"+"[".repeat(32)+"0"+"]".repeat(32)+"}")));
        assertThrows(SettingsException.class,()->LegacySettingsMigration.readJson(bytes("{\"x\":"+"[".repeat(10000)+"0"+"]".repeat(10000)+"}")));
        assertThrows(SettingsException.class,()->LegacySettingsMigration.readJson(bytes("{\"x\":["+"0,".repeat(50000)+"0]}")));
    }
    @Test void everyCurrentDescriptorHasExplicitLegacyMapping(){for(var schema:List.of(SettingsSchema.server(false),SettingsSchema.client()))for(var d:schema.descriptors()){assertFalse(d.legacyKey().isBlank());assertNotNull(d.defaultValue());}}
    @Test void everyMappedFieldMigratesThroughTheRealAdapter() {
        var gson=new com.google.gson.Gson();
        for(var schema:List.of(SettingsSchema.server(false),SettingsSchema.server(true),SettingsSchema.client())) {
            for(var descriptor:schema.descriptors()) {
                String path=descriptor.path();Object oldValue;Object expected;
                switch(descriptor.kind()) {
                    case BOOLEAN -> {expected=!((Boolean)descriptor.defaultValue());oldValue=expected;}
                    case INTEGER -> {expected=descriptor.minimum()==null?3:Math.max(3,descriptor.minimum().intValue());oldValue=expected;}
                    case NUMBER -> {oldValue=4.5;expected=4.5;}
                    case STRING -> {oldValue=path.endsWith("mode")?"off":"minecraft:dirt";expected=oldValue;}
                    case STRING_LIST -> {oldValue=List.of("custom:entry");expected=oldValue;}
                    case STRING_GROUPS -> {oldValue=List.of(List.of("Exact.Case","alternate"));expected=oldValue;}
                    case STRING_MAP -> {oldValue=Map.of("remote:block","minecraft:stone");expected=oldValue;}
                    case INTEGER_MAP -> {oldValue=Map.of("custom:planet",700);expected=oldValue;}
                    default -> throw new AssertionError(descriptor.kind());
                }
                if(path.equals("storage.lod_store.enabled")){oldValue="on";expected=true;}
                if(path.equals("generation.timeout_ticks")){oldValue=75;expected=1500;}
                if(path.equals("updates.dirty_broadcast_interval_ticks")){oldValue=25;expected=500;}
                if(path.equals("lod.distance.by_world")&&!schema.isPaper())expected=Map.of();
                var result=LegacySettingsMigration.migrate(gson.toJson(Map.of(descriptor.legacyKey(),oldValue)).getBytes(StandardCharsets.UTF_8),schema);
                assertEquals(expected,result.values().get(path),schema.side()+" "+schema.isPaper()+" "+path);
            }
        }
    }
    @Test void migrationReportsConversionsAndRedactsPrivateRepairs() {
        var result=LegacySettingsMigration.migrate(bytes("{\"generationTimeoutSeconds\":60,\"farPlayers\":\"OPT-IN\",\"farPlayersExclude\":[null,\"private player\"]}"),SettingsSchema.server(false));
        assertTrue(result.normalizations().stream().anyMatch(n->n.path().equals("generation.timeout_ticks")));
        assertTrue(result.normalizations().stream().anyMatch(n->n.path().equals("far_players.mode")));
        var exclusion=result.normalizations().stream().filter(n->n.path().equals("far_players.excluded_players")).findFirst().orElseThrow();
        assertEquals("<private>",exclusion.requested());assertEquals("<private>",exclusion.effective());
    }
    @Test void curatedMapPreservesLegacyPrimitiveStringCoercionButRejectsStructures() {
        var c=client("{\"crossVersionBlockFallbacks\":{\"numeric\":5,\"boolean\":true}}");
        assertEquals(Map.of("numeric","5","boolean","true"),c.compatibility().blockFallbacks().overrides());
        for(String value:List.of("{}","[]"))assertThrows(SettingsException.class,()->client("{\"crossVersionBlockFallbacks\":{\"bad\":"+value+"}}"));
    }
}
