package dev.vox.lss.sponge;

import dev.vox.lss.common.config.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SpongeConfigValidationTest {
    @Test void spongeUsesTheVanillaTemplateAndTheSharedDimensionDefaults() {
        var c = new SpongeConfig();
        assertEquals(64, c.lodDistanceForWorld("minecraft:the_nether"));
        assertEquals("on", c.lodStore());
        assertFalse(c.requireServicePermission());
        assertEquals(1200, c.generationTimeoutTicks());
        assertEquals(200, c.dirtyBroadcastIntervalTicks());
    }
    @Test void theDimensionIdIsTheOnlyWorldKey() {
        // Every Sponge world is its own dimension, so by_world (a Bukkit world-name map) is not read
        var values = Map.<String,Object>of("lod.distance.default_chunks", 12,
                "lod.distance.by_dimension", Map.of("minecraft:the_nether", 32, "myplugin:arena", 96));
        var c = new SpongeConfig(SettingsSchema.server(false).fromValues(values).normalized());
        assertEquals(32, c.lodDistanceForWorld("minecraft:the_nether"));
        assertEquals(96, c.lodDistanceForWorld("myplugin:arena"));
        assertEquals(12, c.lodDistanceForWorld("custom:void"));
        assertEquals(96, c.maxConfiguredLodDistanceChunks());
    }
    @Test void zeroSentinelsAndCorrelatedBoundsResolveInThePlatformFacade() {
        var c = new SpongeConfig(SettingsSchema.server(false).fromValues(Map.of(
                "generation.concurrency.global", 2, "generation.concurrency.per_player", 10,
                "storage.lod_store.max_size_mib", 0, "updates.dirty_broadcast_interval_ticks", 0,
                "storage.disk.max_concurrent_reads", 64)).normalized());
        assertEquals(2, c.generationLimits().perPlayer());
        assertEquals(Long.MAX_VALUE, c.lodStoreMaxBytes());
        assertEquals(0, c.dirtyBroadcastIntervalTicks());
        assertEquals(8, c.effectiveMaxConcurrentDiskReads(8, true));
    }
}
